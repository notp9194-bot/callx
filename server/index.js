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
  // Cloudinary signature: alphabetical params (folder + timestamp) + secret
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

// ---- Notify single user ----
app.post("/notify", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const {
    toUid, fromUid, fromName, type, text,
    chatId, messageId, mediaUrl
  } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });
  try {
    const snap = await admin.database().ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });
    // Lookup sender profile so the notification can show
    // mobile, photo, online/offline state etc.
    let fromMobile = "", fromPhoto = "", fromLastSeen = "0";
    if (fromUid) {
      try {
        const fSnap = await admin.database()
          .ref("users/" + fromUid).once("value");
        const f = fSnap.val() || {};
        fromMobile   = String(f.mobile || f.callxId || "");
        fromPhoto    = String(f.photoUrl || "");
        fromLastSeen = String(f.lastSeen || 0);
      } catch (e) { /* best-effort */ }
    }
    const message = {
      token: user.fcmToken,
      data: {
        type:         String(type || "message"),
        fromUid:      String(fromUid || ""),
        fromName:     String(fromName || ""),
        fromMobile:   fromMobile,
        fromPhoto:    fromPhoto,
        fromLastSeen: fromLastSeen,
        chatId:       String(chatId || ""),
        messageId:    String(messageId || ""),
        mediaUrl:     String(mediaUrl || ""),
        text:         String(text || "")
      },
      android: { priority: "high" }
    };
    const r = await admin.messaging().send(message);
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ---- Notify group (fanout to all members except sender) ----
app.post("/notify/group", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });
  const { groupId, fromUid, fromName, type, text } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });
  try {
    const gSnap = await admin.database()
      .ref("groups/" + groupId).once("value");
    const g = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });
    const memberUids = Object.keys(g.members || {})
      .filter(uid => uid !== fromUid);
    const tokens = [];
    for (const uid of memberUids) {
      const us = await admin.database().ref("users/" + uid).once("value");
      const t = us.val() && us.val().fcmToken;
      if (t) tokens.push(t);
    }
    if (!tokens.length) return res.json({ ok: true, sent: 0 });
    const responses = [];
    for (const tk of tokens) {
      try {
        const r = await admin.messaging().send({
          token: tk,
          data: {
            type:     String(type || "group_message"),
            groupId:  String(groupId),
            fromUid:  String(fromUid || ""),
            fromName: String((g.name || "Group") + " • " + (fromName || "")),
            text:     String(text || "")
          },
          android: { priority: "high" }
        });
        responses.push(r);
      } catch (e) {
        console.warn("group send fail:", e.message);
      }
    }
    res.json({ ok: true, sent: responses.length });
  } catch (e) {
    console.error("group notify err:", e.message);
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

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log("callx-server v2 on :" + PORT));
