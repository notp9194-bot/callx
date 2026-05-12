const express = require("express");
const cors    = require("cors");
const morgan  = require("morgan");
const crypto  = require("crypto");
const admin   = require("firebase-admin");

const app = express();
app.use(cors());
app.use(express.json({ limit: "2mb" }));
app.use(morgan("tiny"));

// ---- Firebase Admin init ----
let firebaseReady = false;
try {
  const sa = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (sa) {
    const creds = JSON.parse(sa);
    admin.initializeApp({
      credential: admin.credential.cert(creds),
      databaseURL: process.env.DB_URL ||
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app"
    });
    firebaseReady = true;
    console.log("[OK] Firebase Admin initialized");
  } else {
    console.warn("[WARN] FIREBASE_SERVICE_ACCOUNT missing");
  }
} catch (e) {
  console.error("[ERR] Firebase init failed:", e.message);
}

const CLOUD_NAME = process.env.CLOUDINARY_CLOUD_NAME || "dvqqgqdls";
const CLOUD_KEY  = process.env.CLOUDINARY_API_KEY;
const CLOUD_SEC  = process.env.CLOUDINARY_API_SECRET;
const cloudReady = !!(CLOUD_KEY && CLOUD_SEC);
if (!cloudReady) console.warn("[WARN] CLOUDINARY_API_KEY/SECRET missing");

app.get("/", (req, res) => res.json({
  ok: true,
  service: "callx-server v2",
  firebaseReady,
  cloudReady,
  cloudName: CLOUD_NAME
}));
app.get("/healthz", (req, res) =>
  res.json({ ok: true, firebaseReady, cloudReady }));

// ---- Cloudinary signed upload ----
app.post("/cloudinary/sign", (req, res) => {
  if (!cloudReady) {
    return res.status(503).json({
      error: "Cloudinary not configured",
      hint: "Render dashboard pe CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET set karo"
    });
  }
  const folder       = (req.body && req.body.folder) || "callx";
  const resourceType = (req.body && req.body.resource_type) || "auto";
  const timestamp    = Math.floor(Date.now() / 1000).toString();
  const toSign = `folder=${folder}&timestamp=${timestamp}`;
  const signature = crypto.createHash("sha1")
    .update(toSign + CLOUD_SEC).digest("hex");
  res.json({
    signature, timestamp,
    api_key: CLOUD_KEY,
    cloud_name: CLOUD_NAME,
    folder, resource_type: resourceType
  });
});

// â”€â”€ Helper: build history JSON from Firebase snapshot â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
// Returns JSON string: [{"t":"Hi","ts":1234567890,"me":false}, ...]
// "me":true = message sent BY the notification receiver (their own bubble)
function getHistoryJson(histSnap, receiverUid) {
  if (!histSnap || !histSnap.exists()) return "";
  const items = [];
  histSnap.forEach(child => {
    const v    = child.val() || {};
    const type = String(v.type || "text");
    let   t    = String(v.text || "");
    // Fix 7: use actual DB timestamp â€” avoids client clock mismatch reorder
    const ts   = Number(v.timestamp || 0);
    if (ts === 0) return; // skip entries with no timestamp
    const sid  = String(v.senderId || v.fromUid || "");
    if (!t) {
      if (type === "image") t = "\uD83D\uDCF7 Photo";           // ðŸ“·
      else if (type === "video") t = "\uD83C\uDFAC Video";      // ðŸŽ¬
      else if (type === "audio") t = "\uD83C\uDFA4 Voice message"; // ðŸŽ¤
      else if (type === "file" ) t = "\uD83D\uDCCE File";       // ðŸ“Ž
      else if (type === "pdf"  ) t = "\uD83D\uDCC4 PDF document"; // ðŸ“„
      else t = "Message";
    }
    items.push({ t, ts, me: sid === receiverUid });
  });
  items.sort((a, b) => a.ts - b.ts);
  return JSON.stringify(items);
}

// ---- Notify single user (v18 â€” zero Firebase on app side) ----
//
// v18 change: server fetches permaBlocked, blocked, muted, history in ONE
// parallel Promise.all batch and sends them as FCM data flags.
// App receives everything it needs â€” no Firebase calls on device (~10ms).
// myThumb field removed (was undefined); use fromThumb instead on client.
//
app.post("/notify", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const {
    toUid, fromUid, fromName, type, text,
    chatId, messageId, mediaUrl, force
  } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });

  const isCall = (type === "call" || type === "video_call");

  try {
    const db = admin.database();

    // â”€â”€ Step 1: All reads in ONE parallel batch â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    const reads = [
      db.ref("users/" + toUid).once("value"),                                    // [0] receiver
      fromUid ? db.ref("users/" + fromUid).once("value") : Promise.resolve(null),// [1] sender
      (!force && fromUid && !isCall)
        ? db.ref("permaBlocked/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),                                                  // [2] permaBlocked
      (!force && fromUid && !isCall)
        ? db.ref("blocked/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),                                                  // [3] blocked
      (!force && fromUid && !isCall)
        ? db.ref("muted/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),                                                  // [4] muted
      (chatId && !isCall)
        ? db.ref("messages/" + chatId).orderByChild("timestamp").limitToLast(5).once("value")
        : Promise.resolve(null)                                                   // [5] history (last 5 msgs)
    ];

    const [receiverSnap, senderSnap, pbSnap, blockedSnap, mutedSnap, histSnap]
      = await Promise.all(reads);

    // â”€â”€ Step 2: Check receiver â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    const user = receiverSnap ? (receiverSnap.val() || {}) : {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });
    // Fix 6: receiver ka thumb â€” client "me" bubble pe avatar dikhane ke liye
    const myThumb = String(user.thumbUrl || user.photoUrl || "");

    // â”€â”€ Step 3: Block checks (server drops call notifications too) â”€â”€â”€â”€â”€â”€â”€â”€
    const isPermaBlocked = pbSnap && pbSnap.val() === true;
    const isBlocked      = blockedSnap && blockedSnap.val() === true;

    if (isPermaBlocked)
      return res.json({ ok: true, dropped: "permaBlocked" });

    // blocked â†’ still deliver but app shows blocked UI (send flag, don't drop)

    // â”€â”€ Step 4: Sender info â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    let fromMobile = "", fromPhoto = "", fromThumb = "", fromLastSeen = "0";
    if (senderSnap) {
      const f   = senderSnap.val() || {};
      fromMobile   = String(f.mobile   || f.callxId || "");
      fromPhoto    = String(f.photoUrl || "");
      fromThumb    = String(f.thumbUrl || "");
      fromLastSeen = String(f.lastSeen || 0);
    }

    // â”€â”€ Step 5: Last message text â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    const history = getHistoryJson(histSnap, toUid); // toUid = receiver

    // â”€â”€ Step 6: Muted flag â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    const isMuted = mutedSnap && mutedSnap.val() === true;

    // â”€â”€ Step 7: Build FCM message with ALL flags â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€â”€
    const message = {
      token: user.fcmToken,
      data: {
        type:         String(type      || "message"),
        fromUid:      String(fromUid   || ""),
        fromName:     String(fromName  || ""),
        fromMobile:   fromMobile,
        fromPhoto:    fromPhoto,
        fromThumb:    fromThumb,
        fromLastSeen: fromLastSeen,
        chatId:       String(chatId    || ""),
        messageId:    String(messageId || ""),
        mediaUrl:     String(mediaUrl  || ""),
        text:         String(text      || ""),
        // â”€â”€ v18 flags â€” app reads these, zero Firebase calls on device â”€â”€
        // permaBlocked is always "0" here; server drops above if true
        permaBlocked: "0",
        blocked:      (isBlocked  === true) ? "1" : "0",
        muted:        (isMuted    === true) ? "1" : "0",
        history:      history,
        myThumb:      myThumb,
        // â”€â”€ call helper â”€â”€
        ...(isCall && text ? { callId: String(text) } : {})
      },
      android: {
        priority: (isMuted && !isCall) ? "normal" : "high",
        ...(isCall ? { ttl: 30000 } : {})
      }
    };

    const r = await admin.messaging().send(message);
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ---- Notify reel like / comment / comment-like / following-posted (v14 fix) ----
//
// FCM payload keys (received by ReelFCMNotificationHandler on client):
//   reel_notif_type  â†’ "like" | "comment" | "comment_like" | "comment_reply" |
//                       "mention_caption" | "mention_comment" | "new_follower" |
//                       "following_posted" | "duet" | "stitch" | ...
//   sender_uid       â†’ who performed the action
//   sender_name      â†’ display name of actor
//   sender_photo     â†’ avatar URL (fetched from DB if missing)
//   reel_id          â†’ target reel ID
//   reel_thumb       â†’ thumbnail URL for reel preview in notification
//   comment_text     â†’ comment body (for comment / comment_like / comment_reply)
//   comment_id       â†’ comment Firebase key
//
// FIX v14: Android 14+ blocked dataSync foreground service from killed state.
//          Client now uses shortService type â€” no OS restrictions.
//          Server-side: added following_posted, comment_reply, mention_* types.
//
app.post("/notify/reel", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const VALID_REEL_TYPES = new Set([
    "like", "comment", "comment_like", "comment_reply",
    "mention_caption", "mention_comment", "new_follower",
    "following_posted", "duet", "stitch", "video_reply",
    "collab_request", "collab_accepted", "gift",
    "live_started", "live_milestone", "close_friend_live",
    "trending", "viral", "view_milestone", "follower_milestone",
    "upload_complete", "upload_failed", "scheduled_post",
    "scheduled_reminder", "product_tag_click", "creator_fund_payout",
    "content_removed", "report_resolved", "sound_trending",
    "pinned_comment", "close_friend_post", "challenge",
    "reel_shared", "reel_saved", "reel_downloaded",
    "weekly_digest", "collab_live"
  ]);

  const {
    toUid, fromUid, fromName, fromPhoto,
    reelId, reelThumb, type, commentText, commentId
  } = req.body || {};

  if (!toUid)   return res.status(400).json({ error: "toUid required" });
  if (!type)    return res.status(400).json({ error: "type required" });
  if (!VALID_REEL_TYPES.has(type))
    return res.status(400).json({ error: "invalid reel_notif_type: " + type });

  // reelId required for most types except new_follower / weekly_digest / etc
  const noReelIdNeeded = ["new_follower", "weekly_digest", "follower_milestone",
    "creator_fund_payout", "report_resolved", "upload_failed"];
  if (!noReelIdNeeded.includes(type) && !reelId)
    return res.status(400).json({ error: "reelId required for type: " + type });

  // Don't notify yourself
  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    // Fetch receiver's FCM token
    const snap = await admin.database().ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    // Fetch sender's photo if not provided by client
    let senderPhoto = String(fromPhoto || "");
    if (!senderPhoto && fromUid) {
      try {
        const fSnap = await admin.database()
          .ref("users/" + fromUid).once("value");
        const fVal = fSnap.val() || {};
        // thumbUrl prefer karo (small, fast download)
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (e) { /* best-effort */ }
    }

    const message = {
      token: user.fcmToken,
      data: {
        reel_notif_type: String(type        || "like"),
        sender_uid:      String(fromUid     || ""),
        sender_name:     String(fromName    || ""),
        sender_photo:    senderPhoto,
        reel_id:         String(reelId      || ""),
        reel_thumb:      String(reelThumb   || ""),
        comment_text:    String(commentText || ""),
        comment_id:      String(commentId   || ""),
      },
      android: {
        priority: "high",
        ttl: 86400000
      }
    };

    const r = await admin.messaging().send(message);
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("reel notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ---- Notify group (production-grade fanout v2) ----
//
// v2 fixes:
//   Fix 4: msgId key added (client reads "msgId", server was only sending "messageId")
//   Fix 5: mention/priority flags sent in payload
//   Fix 6: permaBlocked + user token fetches batched in ONE Promise.all (not serial inside loop)
//   Fix 7: group history fetched server-side (same as 1-1 notify) â€” zero Firebase on client
//
app.post("/notify/group", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const {
    groupId, fromUid, fromName, fromPhoto,
    messageId, type, text, mediaUrl
  } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });
  try {
    const db = admin.database();
    const gSnap = await db.ref("groups/" + groupId).once("value");
    const g = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const groupName  = String(g.name    || "Group");
    const groupIcon  = String(g.iconUrl || "");
    const memberUids = Object.keys(g.members || {}).filter(uid => uid !== fromUid);
    const mutedBy    = g.mutedBy || {};

    // Fix 6: ONE big Promise.all â€” sender info + history + all permaBlocked + all user tokens
    const sharedReads = [
      fromUid
        ? db.ref("users/" + fromUid).once("value")
        : Promise.resolve(null),                                           // [0] sender
      db.ref("messages/" + groupId)
        .orderByChild("timestamp").limitToLast(5).once("value")           // [1] Fix 7: group history
    ];
    // permaBlocked per member â€” index 2...(2+N-1)
    const pbReads = memberUids.map(uid =>
      fromUid
        ? db.ref("permaBlocked/" + uid + "/" + fromUid).once("value")
        : Promise.resolve(null)
    );
    // user token reads â€” index (2+N)...(2+2N-1)
    const tokenReads = memberUids.map(uid => db.ref("users/" + uid).once("value"));

    const allResults = await Promise.all([...sharedReads, ...pbReads, ...tokenReads]);

    const senderSnap   = allResults[0];
    const histSnap     = allResults[1];
    const pbResults    = allResults.slice(2, 2 + memberUids.length);
    const tokenResults = allResults.slice(2 + memberUids.length);

    let senderPhoto    = String(fromPhoto || "");
    let senderMobile   = "";
    let senderLastSeen = "0";
    if (senderSnap) {
      const f = senderSnap.val() || {};
      if (!senderPhoto) senderPhoto = String(f.thumbUrl || f.photoUrl || "");
      senderMobile    = String(f.mobile || f.callxId || "");
      senderLastSeen  = String(f.lastSeen || 0);
    }

    // Fix 7: Build history JSON from snapshot (same helper as 1-1)
    const history = getHistoryJson(histSnap, null);

    // Fix 14: @mention detection â€” @everyone/@all + individual @name (member displayName se)
    const mentionedUids = new Set();
    if (text) {
      const lower = text.toLowerCase();
      if (lower.includes("@everyone") || lower.includes("@all")) {
        memberUids.forEach(uid => mentionedUids.add(uid));
      } else {
        // Individual @name mention: tokenResults mein user data already hai
        // Match karo har member ke displayName / name against @word tokens
        const mentionTokens = (text.match(/@(\w+)/g) || [])
          .map(t => t.slice(1).toLowerCase());
        if (mentionTokens.length > 0) {
          tokenResults.forEach((snap, idx) => {
            if (!snap) return;
            const u = snap.val() || {};
            const name = String(u.name || u.displayName || "").toLowerCase().replace(/\s+/g, "");
            const first = name.split(" ")[0];
            if (mentionTokens.some(t => name.startsWith(t) || first.startsWith(t))) {
              mentionedUids.add(memberUids[idx]);
            }
          });
        }
      }
    }

    const updates     = {};
    const staleTokens = [];
    let sent = 0, dropped = 0;

    await Promise.all(memberUids.map(async (uid, idx) => {
      try {
        // Fix 6: Use pre-fetched permaBlocked result (no extra Firebase call)
        const pbSnap = pbResults[idx];
        if (pbSnap && pbSnap.val() === true) { dropped++; return; }

        // Fix 6: Use pre-fetched token result
        const u  = tokenResults[idx] ? (tokenResults[idx].val() || {}) : {};
        const tk = u.fcmToken;
        if (!tk) { dropped++; return; }

        const isMuted = mutedBy[uid] === true;
        updates["groups/" + groupId + "/unread/" + uid] =
          admin.database.ServerValue.increment(1);

        await admin.messaging().send({
          token: tk,
          data: {
            type:          String(type || "group_message"),
            groupId:       String(groupId),
            groupName:     groupName,
            groupIcon:     groupIcon,
            fromUid:       String(fromUid   || ""),
            fromName:      String(fromName  || ""),
            fromPhoto:     senderPhoto,
            fromThumb:     senderPhoto,
            fromMobile:    senderMobile,
            fromLastSeen:  senderLastSeen,
            messageId:     String(messageId || ""),
            msgId:         String(messageId || ""),  // Fix 4: client reads "msgId"
            mediaUrl:      String(mediaUrl  || ""),
            text:          String(text      || ""),
            muted:         isMuted ? "1" : "0",
            // Fix 5: mention / priority flags
            mention:       mentionedUids.has(uid) ? "true" : "false",
            priority:      "false",
            // Fix 7: group history from server â€” client skips Firebase
            history:       history
          },
          android: {
            priority:    isMuted ? "normal" : "high",
            // Fix 12: collapseKey per-message â€” fast messages drop nahi honge.
            // messageId unique hota hai; agar missing ho toh timestamp fallback.
            collapseKey: "grp_" + groupId + "_" + (messageId || Date.now()),
            ttl:         24 * 60 * 60 * 1000
          }
        });
        sent++;
      } catch (e) {
        const code = e && (e.code || (e.errorInfo && e.errorInfo.code));
        if (code === "messaging/registration-token-not-registered" ||
            code === "messaging/invalid-registration-token") {
          staleTokens.push(uid);
        } else {
          console.warn("group send fail (" + uid + "):", e && e.message ? e.message : e);
        }
        dropped++;
      }
    }));

    try {
      if (Object.keys(updates).length) await db.ref().update(updates);
    } catch (e) { /* ignore */ }
    try {
      for (const uid of staleTokens) await db.ref("users/" + uid + "/fcmToken").remove();
    } catch (e) { /* ignore */ }

    res.json({ ok: true, sent, dropped, members: memberUids.length });
  } catch (e) {
    console.error("group notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ---- Reset unread counter for a group ----
app.post("/group/markRead", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { groupId, uid } = req.body || {};
  if (!groupId || !uid)
    return res.status(400).json({ error: "groupId & uid required" });
  try {
    await admin.database()
      .ref("groups/" + groupId + "/unread/" + uid).set(0);
    res.json({ ok: true });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ---- Notify status (fanout to all contacts of poster) ----
app.post("/notify/status", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { fromUid, fromName } = req.body || {};
  if (!fromUid) return res.status(400).json({ error: "fromUid required" });
  try {
    const cSnap = await admin.database()
      .ref("contacts/" + fromUid).once("value");
    const contacts = cSnap.val() || {};
    const uids = Object.keys(contacts);
    const tokens = [];
    for (const uid of uids) {
      const us = await admin.database().ref("users/" + uid).once("value");
      const t = us.val() && us.val().fcmToken;
      if (t) tokens.push(t);
    }
    if (!tokens.length) return res.json({ ok: true, sent: 0 });
    let sent = 0;
    for (const tk of tokens) {
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:     "status",
            fromUid:  String(fromUid),
            fromName: String(fromName || "Friend"),
            text:     "Naya status post kiya"
          },
          android: { priority: "high" }
        });
        sent++;
      } catch (e) {
        console.warn("status send fail:", e.message);
      }
    }
    res.json({ ok: true, sent });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ---- Android App Links â€” Digital Asset Links ----
// Ye file Android OS verify karta hai taaki app "verified links" mile
// Path: https://your-domain.com/.well-known/assetlinks.json
const ASSET_LINKS = [
  {
    relation: ["delegate_permission/common.handle_all_urls"],
    target: {
      namespace: "android_app",
      package_name: "com.callx.app",
      sha256_cert_fingerprints: [
        "92:31:CD:9F:90:15:45:54:3B:92:D8:21:FC:6E:1F:DC:D5:40:8B:F0:69:04:96:85:BD:30:99:50:1A:EB:5D:03"
      ]
    }
  }
];

// Route 1: Standard path jo Android verify karta hai
app.get("/.well-known/assetlinks.json", (req, res) => {
  res.setHeader("Content-Type", "application/json");
  res.setHeader("Cache-Control", "public, max-age=3600");
  res.json(ASSET_LINKS);
});

// Route 2: Direct path (fallback / manual test ke liye)
app.get("/assetlinks.json", (req, res) => {
  res.setHeader("Content-Type", "application/json");
  res.json(ASSET_LINKS);
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log("callx-server v2 on :" + PORT));
