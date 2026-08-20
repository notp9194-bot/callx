const express = require("express");
const cors    = require("cors");
const morgan  = require("morgan");
const crypto  = require("crypto");
const admin   = require("firebase-admin");

// ── FFmpeg binary path (Render / any server pe) ───────────────────────────────
try {
  const ffmpegInstaller = require("@ffmpeg-installer/ffmpeg");
  const ffmpegLib       = require("fluent-ffmpeg");
  ffmpegLib.setFfmpegPath(ffmpegInstaller.path);
  console.log("[OK] FFmpeg binary path set:", ffmpegInstaller.path);
} catch (e) {
  console.warn("[WARN] @ffmpeg-installer/ffmpeg not found:", e.message);
}

const app = express();

// ✅ YE LINE ADD KI (game chalane ke liye)
app.use(express.static(__dirname));

app.use(cors());
app.use(express.json({ limit: "2mb" }));
app.use(morgan("tiny"));

// ══════════════════════════════════════════════════════════════════════════════
// Firebase Admin init
// ══════════════════════════════════════════════════════════════════════════════
let firebaseReady = false;
try {
  const sa = process.env.FIREBASE_SERVICE_ACCOUNT;
  if (sa) {
    admin.initializeApp({
      credential: admin.credential.cert(JSON.parse(sa)),
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

// ══════════════════════════════════════════════════════════════════════════════
// TICK ADVANCE #3 — server-side delivery fallback (safety net)
// ══════════════════════════════════════════════════════════════════════════════
// WHY: the client marks "delivered" itself (GlobalDeliveryAckManager / the
// FCM push handler / ChatActivity's own listener). All three are still
// client-side though — if the recipient's app never runs at all after the
// send (force-stopped, notif permission revoked, battery-killed before any
// of those paths fire), the tick stays stuck on "sent" forever with no
// server-side backstop. This job is that backstop.
//
// HOW: the client drops a tiny index entry at deliveryPending/{msgId} =
// {chatId, toUid, ts} right after a message is written as "sent" (see
// MessageStatusSync.markPendingDelivery on the Android side), and removes
// it once delivered/read is confirmed client-side. This job only ever
// scans that small index — never the full messages tree — so cost stays
// flat regardless of total message volume.
//
// HEURISTIC: if the recipient's users/{toUid}/lastSeen is newer than the
// message's ts (i.e. they were reachable/online at some point after the
// message was sent), we treat that as an implicit delivery ACK — this is
// not a true low-level transport receipt, but it is a reasonable and safe
// approximation, and it only ever moves status forward via the same
// transaction pattern the client uses, so it can never downgrade a "read".
const DELIVERY_FALLBACK_INTERVAL_MS = 5 * 60 * 1000; // every 5 min
const DELIVERY_FALLBACK_MIN_AGE_MS  = 2 * 60 * 1000;  // don't race the client — give it 2 min first

async function runDeliveryFallbackJob() {
  if (!firebaseReady) return;
  try {
    const db = admin.database();
    const snap = await db.ref("deliveryPending").once("value");
    if (!snap.exists()) return;
    const now = Date.now();
    const entries = snap.val();

    for (const msgId of Object.keys(entries)) {
      const entry = entries[msgId];
      if (!entry || !entry.chatId || !entry.toUid || !entry.ts) continue;
      if (now - entry.ts < DELIVERY_FALLBACK_MIN_AGE_MS) continue; // too soon, let client win

      try {
        const userSnap = await db.ref(`users/${entry.toUid}/lastSeen`).once("value");
        const lastSeen = userSnap.val();
        if (!lastSeen || lastSeen < entry.ts) continue; // recipient never came online since send

        const statusRef = db.ref(`messages/${entry.chatId}/${msgId}/status`);
        await statusRef.transaction(cur => {
          if (cur === "read" || cur === "seen" || cur === "delivered") return cur; // no downgrade, no dupe write
          return "delivered";
        });
        await db.ref(`messages/${entry.chatId}/${msgId}/deliveredAt`)
                .set(admin.database.ServerValue.TIMESTAMP);
        await db.ref(`deliveryPending/${msgId}`).remove();
      } catch (innerErr) {
        console.warn("[delivery-fallback] entry failed:", msgId, innerErr.message);
      }
    }
  } catch (e) {
    console.warn("[delivery-fallback] job failed:", e.message);
  }
}

if (process.env.NODE_ENV !== "test") {
  setInterval(runDeliveryFallbackJob, DELIVERY_FALLBACK_INTERVAL_MS);
}

// ══════════════════════════════════════════════════════════════════════════════
// LINKED DEVICES — mint a Firebase Auth custom token the instant the phone
// approves a "CallX2 Web" QR pairing.
//
// WHY THIS HAS TO LIVE ON A SERVER (not the phone or the browser):
// signInWithCustomToken lets a client sign in AS a given uid, so only code
// holding the Admin SDK's service-account credentials is allowed to mint
// one — same rule as everything else in this file that calls `admin.*`.
// This server already keeps a live Admin SDK connection open (it's how the
// delivery-fallback job above works too), so a persistent .on() listener
// here is simpler than standing up a separate Cloud Functions deploy for
// the same job — no extra `firebase deploy --only functions` step, no
// second billing surface, one less moving part to keep in sync.
//
// FLOW (see core/linkeddevice/LinkedDeviceManager.java + callx2-web.html):
//   1. Web writes pairingSessions/{code} = {status:'pending', deviceInfo}
//   2. Phone scans the QR, approves, writes status:'approved' + uid + deviceId
//   3. THIS listener fires on that change, mints the token, writes it back
//      as pairingSessions/{code}/customToken
//   4. Web (already listening) signs in with it, then deletes the node —
//      it's single-use, so nothing valid is ever left sitting in the DB.
// ══════════════════════════════════════════════════════════════════════════════
if (firebaseReady) {
  const linkedDb = admin.database();

  linkedDb.ref("pairingSessions").on("child_changed", async snap => {
    try {
      const session = snap.val();
      const pairingCode = snap.key;
      if (!session || session.status !== "approved") return;
      if (session.customToken) return; // already minted — avoid a duplicate token on re-fires
      if (!session.uid || !session.deviceId) {
        console.warn(`[linked-devices] ${pairingCode} approved without uid/deviceId — skipping`);
        return;
      }

      const token = await admin.auth().createCustomToken(session.uid, {
        linkedDevice: true,
        deviceId: session.deviceId
      });
      await linkedDb.ref(`pairingSessions/${pairingCode}/customToken`).set(token);
      await linkedDb.ref(`pairingSessions/${pairingCode}/tokenIssuedAt`)
        .set(admin.database.ServerValue.TIMESTAMP);
      console.log(`[linked-devices] minted companion token uid=${session.uid} device=${session.deviceId}`);
    } catch (e) {
      console.error("[linked-devices] token mint failed:", e.message);
      // Best-effort — deny the session so the web client's own 90s timeout
      // doesn't leave the user staring at "Linked! Signing in…" forever.
      try {
        await linkedDb.ref(`pairingSessions/${snap.key}/status`).set("denied");
      } catch (_) { /* nothing more we can do */ }
    }
  });

  // Housekeeping, same polling style as the delivery-fallback job above:
  // sweep QR codes that expired unapproved, and approved sessions whose
  // token the web client never came back to consume/delete (crashed tab,
  // closed browser mid-handshake, etc).
  const PAIRING_CLEANUP_INTERVAL_MS = 5 * 60 * 1000; // every 5 min
  setInterval(async () => {
    try {
      const snap = await linkedDb.ref("pairingSessions").once("value");
      if (!snap.exists()) return;
      const now = Date.now();
      const updates = {};
      snap.forEach(child => {
        const s = child.val();
        if (!s) return;
        if (s.status === "pending" && s.expiresAt && s.expiresAt < now) {
          updates[child.key] = null;
        } else if (s.status === "approved" && s.tokenIssuedAt && (now - s.tokenIssuedAt) > 120000) {
          updates[child.key] = null;
        }
      });
      if (Object.keys(updates).length) {
        await linkedDb.ref("pairingSessions").update(updates);
        console.log(`[linked-devices] cleaned up ${Object.keys(updates).length} stale pairing session(s)`);
      }
    } catch (e) {
      console.warn("[linked-devices] cleanup job failed:", e.message);
    }
  }, PAIRING_CLEANUP_INTERVAL_MS);

  console.log("[OK] Linked Devices pairing listener attached");
} else {
  console.warn("[WARN] Linked Devices pairing listener NOT attached — Firebase Admin not ready");
}

// ══════════════════════════════════════════════════════════════════════════════
// Cloudinary config
// ══════════════════════════════════════════════════════════════════════════════
const CLOUD_NAME = process.env.CLOUDINARY_CLOUD_NAME || "dvqqgqdls";
const CLOUD_KEY  = process.env.CLOUDINARY_API_KEY;
const CLOUD_SEC  = process.env.CLOUDINARY_API_SECRET;
const cloudReady = !!(CLOUD_KEY && CLOUD_SEC);
if (!cloudReady) console.warn("[WARN] CLOUDINARY_API_KEY/SECRET missing");

// ══════════════════════════════════════════════════════════════════════════════
// Health / root
// ══════════════════════════════════════════════════════════════════════════════
app.get("/", (req, res) => res.json({
  ok: true,
  service: "callx-server v4",
  firebaseReady,
  cloudReady,
  cloudName: CLOUD_NAME
}));
app.get("/healthz", (req, res) =>
  res.json({ ok: true, firebaseReady, cloudReady }));
app.get("/ping", (req, res) =>
  res.json({ ok: true, time: Date.now() }));

// ══════════════════════════════════════════════════════════════════════════════
// Cloudinary signed upload
// ══════════════════════════════════════════════════════════════════════════════
app.post("/cloudinary/sign", (req, res) => {
  if (!cloudReady) {
    return res.status(503).json({
      error: "Cloudinary not configured",
      hint: "Render dashboard pe CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET set karo"
    });
  }
  const folder       = (req.body && req.body.folder)        || "callx";
  const resourceType = (req.body && req.body.resource_type) || "auto";
  // ✅ HLS support: optional eager transform, e.g. "sp_full_hd/m3u8" —
  // requested by VideoUploader.java when uploading reel videos so Cloudinary
  // returns an adaptive-streaming manifest alongside the normal upload.
  // Must be included in the signed string (alphabetically: eager < folder <
  // timestamp) exactly like /cloudinary/sign/video already does below, or
  // Cloudinary rejects the upload with an invalid-signature error.
  const eager        = (req.body && req.body.eager) || "";
  const timestamp    = Math.floor(Date.now() / 1000).toString();
  let toSign          = `folder=${folder}&timestamp=${timestamp}`;
  if (eager) toSign = `eager=${eager}&` + toSign;
  const signature    = crypto.createHash("sha1")
    .update(toSign + CLOUD_SEC).digest("hex");
  res.json({
    signature, timestamp,
    api_key:       CLOUD_KEY,
    cloud_name:    CLOUD_NAME,
    folder,
    resource_type: resourceType,
    eager
  });
});

// ══════════════════════════════════════════════════════════════════════════════
// Cloudinary VIDEO signed upload — eager transform support
// POST /cloudinary/sign/video
// Body: { folder, eager }
// Response: { signature, timestamp, api_key, cloud_name, folder, eager }
// ══════════════════════════════════════════════════════════════════════════════
app.post("/cloudinary/sign/video", (req, res) => {
  if (!cloudReady) {
    return res.status(503).json({
      error: "Cloudinary not configured",
      hint: "Render dashboard pe CLOUDINARY_API_KEY / CLOUDINARY_API_SECRET set karo"
    });
  }
  const folder    = (req.body && req.body.folder) || "callx/videos/file";
  const eager     = (req.body && req.body.eager)  || "";
  const timestamp = Math.floor(Date.now() / 1000).toString();

  // Signature string — eager include karo agar present hai
  let toSign = `folder=${folder}&timestamp=${timestamp}`;
  if (eager) toSign = `eager=${eager}&` + toSign;

  const signature = crypto.createHash("sha1")
    .update(toSign + CLOUD_SEC).digest("hex");

  res.json({
    signature, timestamp,
    api_key:    CLOUD_KEY,
    cloud_name: CLOUD_NAME,
    folder,
    eager
  });
});

// ══════════════════════════════════════════════════════════════════════════════
// CHAT MESSAGE TRANSLATION — POST /translate
// Body: { text, target }  (target = 2-letter lang code, e.g. "hi", "en")
// Response: { translated, detectedLang }
//
// Proxies to Google's free (unofficial, no API key / billing) translate
// endpoint from the SERVER instead of the phone — keeps the client simple
// and avoids per-device rate-limiting on translate.googleapis.com.
// This is separate from the Cloudinary "Google Translation" add-on (that
// one only translates Cloudinary asset tags, not arbitrary chat text).
// ══════════════════════════════════════════════════════════════════════════════
app.post("/translate", (req, res) => {
  const text   = ((req.body && req.body.text)   || "").toString();
  const target = ((req.body && req.body.target) || "en").toString();

  if (!text.trim()) {
    return res.status(400).json({ error: "text is required" });
  }

  const https = require("https");
  const url = "https://translate.googleapis.com/translate_a/single"
    + "?client=gtx&sl=auto&dt=t&tl=" + encodeURIComponent(target)
    + "&q=" + encodeURIComponent(text);

  https.get(url, gRes => {
    let data = "";
    gRes.on("data", chunk => { data += chunk; });
    gRes.on("end", () => {
      try {
        const parsed = JSON.parse(data);
        const segments = parsed[0] || [];
        const translated = segments.map(seg => seg[0] || "").join("");
        const detectedLang = parsed[2] || "";
        if (!translated) {
          return res.status(502).json({ error: "Empty translation" });
        }
        res.json({ translated, detectedLang });
      } catch (e) {
        console.error("[translate] parse failed:", e.message);
        res.status(502).json({ error: "Translate parse failed: " + e.message });
      }
    });
  }).on("error", e => {
    console.error("[translate] request failed:", e.message);
    res.status(502).json({ error: "Translate request failed: " + e.message });
  });
});

// ══════════════════════════════════════════════════════════════════════════════
// VIDEO COMPRESS — Android v25 server-side compression endpoint
// POST /compress/video  (multipart/form-data)
//
// Mobile ne raw video bheja → server FFmpeg se compress karta hai →
// Cloudinary pe upload karta hai → URL return karta hai
//
// Fields:
//   file            — raw MP4 video
//   api_key         — Cloudinary API key (sign se aata hai)
//   timestamp       — Cloudinary timestamp
//   signature       — Cloudinary signature
//   cloud_name      — Cloudinary cloud name
//   quality_preset  — "360p" / "480p" / "720p" / "1080p" / "original"
//   original_width  — original video width (int)
//   original_height — original video height (int)
//   duration_ms     — video duration in ms (int)
//
// Response: { video_url, thumb_url, public_id, compressed_bytes }
// ══════════════════════════════════════════════════════════════════════════════
(function setupVideoCompress() {
  let multer, cloudinary, ffmpeg, fs, os, path, execFile;

  try {
    multer     = require("multer");
    cloudinary = require("cloudinary").v2;
    ffmpeg     = require("fluent-ffmpeg");
    fs         = require("fs");
    os         = require("os");
    path       = require("path");
    execFile   = require("child_process").execFile;
  } catch (e) {
    console.warn("[WARN] /compress/video deps missing:", e.message,
      "→ npm install multer cloudinary fluent-ffmpeg");
    // Stub endpoint — returns 503 with helpful message
    app.post("/compress/video", (req, res) => {
      res.status(503).json({
        error: "Server video compress ready nahi hai",
        hint: "npm install multer cloudinary fluent-ffmpeg"
      });
    });
    return;
  }

  const upload = multer({
    dest: os.tmpdir(),
    limits: { fileSize: 500 * 1024 * 1024 } // 500 MB max
  });

  // Quality preset → FFmpeg params mapping
  const QUALITY_MAP = {
    "360p":    { scale: "scale=-2:360",   vb: "500k"  },
    "480p":    { scale: "scale=-2:480",   vb: "1000k" },
    "720p":    { scale: "scale=-2:720",   vb: "2000k" },
    "1080p":   { scale: "scale=-2:1080",  vb: "4000k" },
    "original": null  // skip FFmpeg, direct upload
  };

  app.post("/compress/video", upload.single("file"), async (req, res) => {
    if (!cloudReady) {
      return res.status(503).json({ error: "Cloudinary not configured" });
    }
    if (!req.file) {
      return res.status(400).json({ error: "file field required" });
    }

    const inputPath  = req.file.path;
    const outputPath = inputPath + "_compressed.mp4";

    const {
      api_key, timestamp, signature, cloud_name,
      quality_preset = "480p",
      original_width, original_height, duration_ms
    } = req.body;

    // Cloudinary credentials — request fields prefer karo, env fallback
    const cldKey    = api_key    || CLOUD_KEY;
    const cldCloud  = cloud_name || CLOUD_NAME;
    const cldSig    = signature;
    const cldTs     = timestamp;

    cloudinary.config({
      cloud_name:  cldCloud,
      api_key:     cldKey,
      api_secret:  CLOUD_SEC
    });

    const preset = QUALITY_MAP[quality_preset] || QUALITY_MAP["480p"];

    try {
      // ── Step 1: FFmpeg compress (skip if "original") ──────────────────────
      let uploadFile = inputPath;

      if (preset) {
        await new Promise((resolve, reject) => {
          let cmd = ffmpeg(inputPath)
            .videoCodec("libx264")
            .audioCodec("aac")
            .outputOptions([
              "-vf",      preset.scale,
              "-b:v",     preset.vb,
              "-preset",  "fast",
              "-movflags", "+faststart",
              "-y"
            ])
            .on("end", resolve)
            .on("error", reject)
            .save(outputPath);
        });
        uploadFile = outputPath;
      }

      const compressedBytes = fs.statSync(uploadFile).size;

      // ── Step 2: Upload compressed video to Cloudinary ─────────────────────
      const videoUploadOpts = {
        resource_type: "video",
        folder:        "callx/videos/file",
        ...(cldSig && cldTs ? {
          signature:  cldSig,
          timestamp:  cldTs,
          api_key:    cldKey
        } : {})
      };

      const videoResult = await cloudinary.uploader.upload(uploadFile, videoUploadOpts);

      // ── Step 3: Generate thumbnail via Cloudinary eager ───────────────────
      let thumbUrl = "";
      try {
        const thumbResult = await cloudinary.uploader.upload(uploadFile, {
          resource_type: "video",
          folder:        "callx/videos/thumb",
          eager: [{ width: 400, height: 400, crop: "fill", format: "jpg" }]
        });
        thumbUrl = thumbResult.eager?.[0]?.secure_url || "";
      } catch (thumbErr) {
        console.warn("[compress/video] thumb upload failed:", thumbErr.message);
        // Thumb fail hone pe video still return karo
      }

      res.json({
        video_url:        videoResult.secure_url,
        thumb_url:        thumbUrl,
        public_id:        videoResult.public_id,
        compressed_bytes: compressedBytes
      });

    } catch (err) {
      console.error("[compress/video] failed:", err.message);
      res.status(500).json({ error: err.message });
    } finally {
      // Temp files cleanup
      try { if (fs.existsSync(inputPath))  fs.unlinkSync(inputPath);  } catch (_) {}
      try { if (fs.existsSync(outputPath)) fs.unlinkSync(outputPath); } catch (_) {}
    }
  });

  console.log("[OK] /compress/video endpoint ready");

  // ════════════════════════════════════════════════════════════════════════
  // AUDIO FINGERPRINT MATCHING — Instagram-style "same audio, no explicit
  // 'Use Audio' pick" detection.
  //
  // Case this solves: A posts a reel, its audio becomes sounds/orig_{A's
  // reelId}. B has the SAME video file (or just the same audio track) and
  // uploads it raw — never taps "Use this sound". Without this, B's reel
  // silently mints its OWN "orig_{B's reelId}" sound and the two reels never
  // link up. With this, B's upload gets fingerprinted, matched against A's
  // fingerprint, and B's reel is linked to A's existing sound instead.
  //
  // WHY THIS APPROACH (free, no native binary dependency):
  //   - No paid ACR service, no fpcalc/chromaprint system binary to install
  //     on Render (that binary isn't reliably available there) — just a
  //     pure-Node FFT + a Shazam-style "peak frequency per band per frame"
  //     landmark hash, using the FFmpeg binary we already ship for /compress.
  //   - Matching uses an INVERTED INDEX (audio_hash_index/{hash} → sound_id)
  //     in the same Firebase RTDB already used everywhere else in this file,
  //     so a new upload only needs to look up a small sample of its own
  //     hashes rather than compare against every stored fingerprint.
  //
  // ── v2 UPGRADE: offset-consistent voting + noise/compression tolerance ──
  //   1) PARTIAL / OFFSET MATCHING — every hash now carries the FRAME INDEX
  //      it occurred at (both when stored in the index and when queried).
  //      A genuine match — even if the upload is only a 10s slice of a 60s
  //      original — produces votes that all agree on ONE (refT - queryT)
  //      offset, because it's the same audio just shifted in time. Random
  //      hash collisions from unrelated clips scatter across many different
  //      offsets instead. So we now bucket votes by (sound_id, offsetBin)
  //      rather than by sound_id alone — this is exactly what makes Shazam
  //      itself robust to trimmed/offset clips, and it doubles as a much
  //      stronger anti-false-positive filter than a raw vote count ever was.
  //      The winning bucket's offset is reported back as offset_sec: where,
  //      inside the ORIGINAL track, this upload's audio actually begins.
  //   2) NOISE / COMPRESSION TOLERANCE — two independent changes:
  //        a) Peak bins are quantized (AUDIO_QUANT_STEP) before hashing, so
  //           the small bin-shift a re-encode/trim/bitrate-change tends to
  //           introduce no longer flips the hash to a completely different
  //           value.
  //        b) Near-silent frames are skipped entirely (AUDIO_MIN_FRAME_ENERGY)
  //           so lossy re-encode noise-floor artifacts in quiet passages
  //           don't mint garbage hashes that dilute the real match.
  //      NOTE (honest limitation): this does not attempt tempo/speed-change
  //      invariance — a pitch/speed-altered clip needs chroma+DTW-style
  //      matching, which is a materially bigger system than a landmark-hash
  //      index. AUDIO_DELTA_BIN_HOPS gives a little slack for encoder frame-
  //      alignment jitter, not for deliberate speed changes.
  //
  // POST /audio/match  (multipart/form-data)
  //   file          — extracted audio (m4a/aac; anything FFmpeg can decode)
  //   uid           — uploader's uid
  //   reel_id       — this reel's id
  //   new_sound_id  — id the app will use if this turns out to be a genuinely
  //                   NEW original (Android sends "orig_{reelId}" — see
  //                   VideoUploader.uploadOriginalAudio). Keeping this ID
  //                   space shared between Firebase sounds/{id} and this
  //                   fingerprint index is what lets a MATCH's sound_id be
  //                   used directly as an existing sounds/{id} lookup.
  //
  // Response: { matched, sound_id, owner_uid, offset_sec }
  //   matched=true  → sound_id belongs to an EARLIER reel's audio (possibly
  //                   this same uploader's own earlier reel); owner_uid is
  //                   that original creator; offset_sec is where in that
  //                   original track this upload's audio starts (0 if it's
  //                   basically the same start point).
  //   matched=false → sound_id === the new_sound_id you sent; this upload
  //                   is now registered as the original for future matches;
  //                   owner_uid === uid you sent; offset_sec is 0.
  // ════════════════════════════════════════════════════════════════════════
  (function setupAudioFingerprint() {

    // ── Tiny in-place radix-2 FFT (Cooley-Tukey), no dependency needed ──────
    function fftRadix2(re, im) {
      const n = re.length;
      for (let i = 1, j = 0; i < n; i++) {
        let bit = n >> 1;
        for (; j & bit; bit >>= 1) j ^= bit;
        j ^= bit;
        if (i < j) {
          let tr = re[i]; re[i] = re[j]; re[j] = tr;
          let ti = im[i]; im[i] = im[j]; im[j] = ti;
        }
      }
      for (let len = 2; len <= n; len <<= 1) {
        const ang = -2 * Math.PI / len;
        const wr  = Math.cos(ang), wi = Math.sin(ang);
        for (let i = 0; i < n; i += len) {
          let curWr = 1, curWi = 0;
          const half = len / 2;
          for (let j = 0; j < half; j++) {
            const ur = re[i + j],        ui = im[i + j];
            const vr = re[i + j + half] * curWr - im[i + j + half] * curWi;
            const vi = re[i + j + half] * curWi + im[i + j + half] * curWr;
            re[i + j]        = ur + vr; im[i + j]        = ui + vi;
            re[i + j + half] = ur - vr; im[i + j + half] = ui - vi;
            const nwr = curWr * wr - curWi * wi;
            const nwi = curWr * wi + curWi * wr;
            curWr = nwr; curWi = nwi;
          }
        }
      }
    }

    // ── FFmpeg: extract mono 11025Hz raw PCM (s16le) from any audio/video ──
    const SAMPLE_RATE = 11025;

    function extractPcmMono11025(inputPath) {
      return new Promise((resolve, reject) => {
        const outPath = inputPath + "_fp.pcm";
        ffmpeg(inputPath)
          .noVideo()
          .audioChannels(1)
          .audioFrequency(SAMPLE_RATE)
          .outputOptions(["-f", "s16le", "-acodec", "pcm_s16le"])
          .on("end", () => {
            try {
              const buf = fs.readFileSync(outPath);
              fs.unlink(outPath, () => {});
              resolve(buf);
            } catch (e) { reject(e); }
          })
          .on("error", reject)
          .save(outPath);
      });
    }

    // ── Shazam-style landmark hash: peak bin per frequency band per frame ──
    const FFT_SIZE = 1024;
    const HOP      = 512;
    // Bin ranges tuned for 11025Hz / 1024-pt FFT (bin width ≈ 10.77 Hz)
    const BANDS = [[3, 10], [10, 20], [20, 40], [40, 80], [80, 180]];

    // ── FAN-OUT: hash each PAIR of band-peaks separately instead of combining
    // all 5 into one hash. This is the actual fix that makes noise/compression
    // tolerance work — a single 5-way-combined hash needs all 5 bins correct
    // simultaneously to match at all (measured: <5% survival under mild re-
    // encode noise, even with generous quantization). Splitting into several
    // redundant 2-band hashes means one corrupted band only kills the pairs
    // that used it — the rest still land in the index and vote correctly.
    // (measured: ~65-83% hash survival under the same noise, see test notes.)
    const PAIRS = [[0, 1], [1, 2], [2, 3], [3, 4], [0, 2], [1, 3]];

    // Quantizing bins before hashing absorbs the small peak-bin drift a
    // re-encode/trim/bitrate-change tends to introduce. Combined with the
    // fan-out above (not a substitute for it — quantization alone barely
    // helps a fragile single combined hash).
    const QUANT_STEP        = parseInt(process.env.AUDIO_QUANT_STEP, 10)        || 3;
    // Frames quieter than this (mean squared sample amplitude, 0..1 scale)
    // are skipped — lossy re-encode noise-floor artifacts in near-silent
    // passages otherwise mint hashes that only add noise, never real votes.
    const MIN_FRAME_ENERGY  = parseFloat(process.env.AUDIO_MIN_FRAME_ENERGY)    || 0.0015;

    function computeFingerprintHashes(pcmBuffer) {
      const numSamples = Math.floor(pcmBuffer.length / 2); // 16-bit samples
      if (numSamples < FFT_SIZE) return [];

      const window = new Float64Array(FFT_SIZE);
      for (let i = 0; i < FFT_SIZE; i++) {
        window[i] = 0.5 - 0.5 * Math.cos((2 * Math.PI * i) / (FFT_SIZE - 1));
      }

      // hash(string) -> first frame index it occurred at. Deduping to first
      // occurrence keeps the index write cost bounded; the frame index is
      // what enables offset-consistent voting below.
      const hashMap = new Map();
      const re = new Float64Array(FFT_SIZE);
      const im = new Float64Array(FFT_SIZE);

      for (let start = 0; start + FFT_SIZE <= numSamples; start += HOP) {
        const frameIndex = start / HOP;

        let energy = 0;
        for (let i = 0; i < FFT_SIZE; i++) {
          const s = pcmBuffer.readInt16LE((start + i) * 2) / 32768;
          energy += s * s;
          re[i] = s * window[i];
          im[i] = 0;
        }
        energy /= FFT_SIZE;
        if (energy < MIN_FRAME_ENERGY) continue; // near-silent — skip, don't hash noise

        fftRadix2(re, im);

        const peaks = [];
        for (const [lo, hi] of BANDS) {
          let maxMag = -1, maxBin = lo;
          for (let b = lo; b <= hi && b < FFT_SIZE / 2; b++) {
            const mag = re[b] * re[b] + im[b] * im[b];
            if (mag > maxMag) { maxMag = mag; maxBin = b; }
          }
          // Quantize AFTER finding the true peak — the true peak location
          // is what makes the pick meaningful, quantizing just widens the
          // "same peak" tolerance for the hash itself.
          peaks.push(Math.floor(maxBin / QUANT_STEP));
        }

        for (let p = 0; p < PAIRS.length; p++) {
          const [a, b] = PAIRS[p];
          const hash = ((peaks[a] & 0xFF)) | ((peaks[b] & 0xFF) << 8) | ((p & 0xF) << 16);
          const key = String(hash >>> 0);
          if (!hashMap.has(key)) hashMap.set(key, frameIndex);
        }
      }

      const out = [];
      for (const [h, t] of hashMap) out.push({ h, t });
      return out;
    }

    // ── Match against the inverted index, or register as new ───────────────
    // Tunable via env — no redeploy-code-change needed to adjust sensitivity,
    // just update the env var on Render and restart.
    //   MATCH_QUERY_SAMPLE — hashes actually queried, keeps latency sane
    //   MATCH_THRESHOLD    — fraction of sampled hashes that must hit the
    //                        same sound (ratio-only was giving false
    //                        positives on short/quiet clips where the sample
    //                        itself is tiny — e.g. 3/8 = 37% looks confident
    //                        but is really just noise). Lowered default from
    //                        0.30 → 0.22 since real same-audio uploads were
    //                        landing at 25-30%, not the 30%+ assumed earlier.
    //   MATCH_MIN_VOTES    — NEW: absolute floor on votes, independent of
    //                        ratio, so a 1/2 "match" on a near-silent clip
    //                        can't sneak past the ratio check.
    //   DELTA_BIN_HOPS     — width (in hops) of the alignment bucket used to
    //                        group votes by (sound_id, offset). Small values
    //                        demand near-exact frame alignment; too small and
    //                        genuine matches split their votes across
    //                        neighboring buckets and lose to the ratio/min-
    //                        votes floor.
    // Defaults bumped for the v2 fan-out hasher (~2-3x more hashes per second
    // of audio than the old single-combined-hash design — see PAIRS above).
    const MATCH_QUERY_SAMPLE  = parseInt(process.env.AUDIO_MATCH_QUERY_SAMPLE, 10) || 90;
    const MATCH_THRESHOLD     = parseFloat(process.env.AUDIO_MATCH_THRESHOLD)     || 0.25;
    const MATCH_MIN_VOTES     = parseInt(process.env.AUDIO_MATCH_MIN_VOTES, 10)   || 10;
    const INDEX_HASH_CAP      = parseInt(process.env.AUDIO_INDEX_HASH_CAP, 10)    || 1000;
    const MIN_HASHES_REQUIRED = parseInt(process.env.AUDIO_MIN_HASHES, 10)        || 30;
    const DELTA_BIN_HOPS      = parseInt(process.env.AUDIO_DELTA_BIN_HOPS, 10)    || 2;

    async function matchOrCreateSoundId({ hashes, ownerUid, reelId, newSoundId }) {
      const db = admin.database();

      const step = Math.max(1, Math.floor(hashes.length / MATCH_QUERY_SAMPLE));
      const sample = [];
      for (let i = 0; i < hashes.length && sample.length < MATCH_QUERY_SAMPLE; i += step) {
        sample.push(hashes[i]);
      }

      // Votes are bucketed by (sound_id, offsetBin) — NOT sound_id alone.
      // A real match (even a short slice of a longer original) agrees on
      // one consistent (refT - queryT) offset; unrelated collisions don't.
      // See the big comment above setupAudioFingerprint for why.
      const votes = {};
      await Promise.all(sample.map(async ({ h, t: queryT }) => {
        try {
          const snap = await db.ref(`audio_hash_index/${h}`).once("value");
          if (!snap.exists()) return;
          const matches = snap.val(); // { soundId: refFrameIndex, ... }
          for (const [soundId, refT] of Object.entries(matches)) {
            if (typeof refT !== "number") continue; // guards old-format `true` entries pre-upgrade
            const deltaBin = Math.round((refT - queryT) / DELTA_BIN_HOPS);
            const key = soundId + "|" + deltaBin;
            votes[key] = (votes[key] || 0) + 1;
          }
        } catch (_) { /* one bad lookup shouldn't sink the whole match */ }
      }));

      let bestKey = null, bestVotes = 0;
      for (const [key, count] of Object.entries(votes)) {
        if (count > bestVotes) { bestVotes = count; bestKey = key; }
      }

      let bestId = null, bestDeltaBin = 0;
      if (bestKey) {
        const sep = bestKey.lastIndexOf("|");
        bestId = bestKey.slice(0, sep);
        bestDeltaBin = parseInt(bestKey.slice(sep + 1), 10);
      }

      const ratio = sample.length > 0 ? bestVotes / sample.length : 0;
      const isMatch = !!bestId && bestVotes >= MATCH_MIN_VOTES && ratio >= MATCH_THRESHOLD;

      if (isMatch) {
        const metaSnap = await db.ref(`audio_fingerprints/${bestId}`).once("value");
        const meta = metaSnap.val() || {};
        // Where in the ORIGINAL track this upload's audio starts — lets the
        // caller know it matched a portion of a longer sound, not just
        // "some" match.
        const offsetSec = Math.max(0, (bestDeltaBin * DELTA_BIN_HOPS * HOP) / SAMPLE_RATE);
        console.log(`[audio-match] MATCHED sound=${bestId} votes=${bestVotes}/${sample.length} (${(ratio*100).toFixed(1)}%) offset=${offsetSec.toFixed(2)}s`);
        return { matched: true, sound_id: bestId, owner_uid: meta.ownerUid || "", offset_sec: Number(offsetSec.toFixed(2)) };
      }

      if (bestId) {
        console.log(`[audio-match] no match (best was ${bestId} votes=${bestVotes}/${sample.length}, ${(ratio*100).toFixed(1)}% — below threshold)`);
      }

      // No match — this becomes the new original. Use the ID the app already
      // intends to create in Firebase's sounds/ tree, so the two stay in sync.
      const soundId = newSoundId && newSoundId.trim() ? newSoundId : db.ref("audio_fingerprints").push().key;

      await db.ref(`audio_fingerprints/${soundId}`).set({
        ownerUid:  ownerUid || null,
        reelId:    reelId || null,
        createdAt: admin.database.ServerValue.TIMESTAMP,
        hashCount: hashes.length
      });

      // Store each hash's FRAME INDEX (not just `true`) — this is what a
      // future upload's query needs to compute an alignment offset against.
      const capped = hashes.slice(0, INDEX_HASH_CAP);
      const updates = {};
      for (const { h, t } of capped) updates[`audio_hash_index/${h}/${soundId}`] = t;
      if (Object.keys(updates).length) await db.ref().update(updates);

      return { matched: false, sound_id: soundId, owner_uid: ownerUid || "", offset_sec: 0 };
    }

    // ── ASYNC QUEUE ──────────────────────────────────────────────────────
    // WHY: FFT + PCM extraction is CPU-bound and, on Render's free/shared
    // CPU, can take several seconds per clip — doing this synchronously
    // inside the request meant every concurrent reel upload fought for the
    // same CPU and the HTTP connection sat open (risking client timeouts)
    // the whole time. Now the endpoint just accepts the file, queues the
    // job, and returns immediately; a small in-process worker pool (bounded
    // concurrency, so heavy load can't starve the rest of this server —
    // /compress/video, deliveries, etc. — of CPU) processes jobs one at a
    // time per slot and writes the result to Firebase. The app listens for
    // that result via a normal Firebase RTDB listener (see
    // VideoUploader.matchAudioFingerprint on the Android side) — same
    // pattern already used for the linked-devices pairing flow above.
    const FP_QUEUE_CONCURRENCY = parseInt(process.env.AUDIO_FP_CONCURRENCY, 10) || 2;
    const FP_JOB_TTL_MS        = 60 * 60 * 1000; // job status nodes cleaned up after 1h

    const fpQueue = [];
    let   fpActive = 0;

    function enqueueFingerprintJob(job) {
      fpQueue.push(job);
      pumpFingerprintQueue();
    }

    function pumpFingerprintQueue() {
      while (fpActive < FP_QUEUE_CONCURRENCY && fpQueue.length > 0) {
        const job = fpQueue.shift();
        fpActive++;
        runFingerprintJob(job).finally(() => {
          fpActive--;
          pumpFingerprintQueue();
        });
      }
    }

    async function runFingerprintJob({ jobId, inputPath, uid, reelId, newSoundId }) {
      const db = admin.database();
      const jobRef = db.ref(`audio_match_jobs/${jobId}`);
      try {
        await jobRef.update({ status: "processing" });

        const pcm = await extractPcmMono11025(inputPath);
        const hashes = computeFingerprintHashes(pcm);

        let result;
        if (hashes.length < MIN_HASHES_REQUIRED) {
          result = { matched: false, sound_id: newSoundId || "", owner_uid: uid, offset_sec: 0 };
        } else {
          result = await matchOrCreateSoundId({ hashes, ownerUid: uid, reelId, newSoundId });
        }

        await jobRef.update({
          status: "done",
          matched: result.matched,
          sound_id: result.sound_id,
          owner_uid: result.owner_uid,
          offset_sec: result.offset_sec || 0,
          completedAt: admin.database.ServerValue.TIMESTAMP
        });
      } catch (err) {
        console.error(`[audio-match] job ${jobId} failed:`, err.message);
        try {
          await jobRef.update({
            status: "error",
            error: err.message,
            completedAt: admin.database.ServerValue.TIMESTAMP
          });
        } catch (_) { /* best effort — client's bounded wait will just time out */ }
      } finally {
        try { if (fs.existsSync(inputPath)) fs.unlinkSync(inputPath); } catch (_) {}
      }
    }

    // Housekeeping: sweep job status nodes older than FP_JOB_TTL_MS so
    // audio_match_jobs/ doesn't grow forever (mirrors the pairing-session
    // and delivery-fallback cleanup jobs elsewhere in this file).
    if (firebaseReady) {
      setInterval(async () => {
        try {
          const db = admin.database();
          const snap = await db.ref("audio_match_jobs").once("value");
          if (!snap.exists()) return;
          const now = Date.now();
          const jobs = snap.val();
          const updates = {};
          for (const jobId of Object.keys(jobs)) {
            const j = jobs[jobId];
            const ts = j.completedAt || j.createdAt;
            if (ts && now - ts > FP_JOB_TTL_MS) updates[jobId] = null;
          }
          if (Object.keys(updates).length) await db.ref("audio_match_jobs").update(updates);
        } catch (e) {
          console.warn("[audio-match] job cleanup failed:", e.message);
        }
      }, 15 * 60 * 1000); // every 15 min
    }

    const matchUpload = multer({
      dest: os.tmpdir(),
      limits: { fileSize: 30 * 1024 * 1024 } // 30 MB — audio-only file, small
    });

    // POST /audio/match — now returns IMMEDIATELY with a job_id (202) instead
    // of blocking on FFT. Client listens on audio_match_jobs/{job_id} in
    // Firebase RTDB for the result (status: queued → processing → done/error).
    app.post("/audio/match", matchUpload.single("file"), async (req, res) => {
      if (!firebaseReady) {
        return res.status(503).json({ error: "Firebase not configured" });
      }
      if (!req.file) {
        return res.status(400).json({ error: "file field required" });
      }

      const inputPath = req.file.path;
      const { uid = "", reel_id = "", new_sound_id = "" } = req.body;

      try {
        const db = admin.database();
        const jobRef = db.ref("audio_match_jobs").push();
        const jobId  = jobRef.key;

        await jobRef.set({
          status: "queued",
          uid, reelId: reel_id, newSoundId: new_sound_id,
          createdAt: admin.database.ServerValue.TIMESTAMP
        });

        enqueueFingerprintJob({ jobId, inputPath, uid, reelId: reel_id, newSoundId: new_sound_id });

        res.status(202).json({ job_id: jobId, status: "queued" });
      } catch (err) {
        console.error("[/audio/match] enqueue failed:", err.message);
        try { if (fs.existsSync(inputPath)) fs.unlinkSync(inputPath); } catch (_) {}
        res.status(500).json({ error: err.message });
      }
    });

    // GET /audio/match/status/:jobId — polling fallback for clients that
    // can't/don't want a live Firebase listener (e.g. a simple curl check).
    app.get("/audio/match/status/:jobId", async (req, res) => {
      if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
      try {
        const snap = await admin.database().ref(`audio_match_jobs/${req.params.jobId}`).once("value");
        if (!snap.exists()) return res.status(404).json({ error: "job not found" });
        res.json(snap.val());
      } catch (err) {
        res.status(500).json({ error: err.message });
      }
    });

    console.log(`[OK] /audio/match endpoint ready (async queue, concurrency=${FP_QUEUE_CONCURRENCY})`);
  })();
})();

// ══════════════════════════════════════════════════════════════════════════════
// IMAGE COMPRESS — Server-side image compression (Mobile CPU zero load)
// POST /compress/image  (multipart/form-data)
//
// Mobile ne raw image bheji → server sharp se resize + WebP compress kare →
// Cloudinary pe upload kare → { image_url, thumb_url } return kare
//
// Fields:
//   file   — raw image (JPEG/PNG/HEIC etc)
//   folder — optional Cloudinary folder (default: callx/image)
//
// Response: { image_url, thumb_url, compressed_bytes, thumb_bytes }
// ══════════════════════════════════════════════════════════════════════════════
(function setupImageCompress() {
  let multer, sharp, cloudinary, fs, os, path;

  try {
    multer    = require("multer");
    sharp     = require("sharp");
    cloudinary = require("cloudinary").v2;
    fs        = require("fs");
    os        = require("os");
    path      = require("path");
  } catch (e) {
    console.warn("[WARN] /compress/image deps missing:", e.message,
      "→ npm install sharp multer cloudinary");
    app.post("/compress/image", (req, res) => {
      res.status(503).json({
        error: "Server image compress ready nahi hai",
        hint: "npm install sharp"
      });
    });
    return;
  }

  const upload = multer({
    dest: os.tmpdir(),
    limits: { fileSize: 50 * 1024 * 1024 } // 50 MB max
  });

  // Full image settings
  const FULL_MAX_PX  = 1280;
  const FULL_QUALITY = 80;
  const FULL_MAX_KB  = 800;

  // Thumbnail settings
  const THUMB_SIZE    = 200;
  const THUMB_QUALITY = 65;

  app.post("/compress/image", upload.single("file"), async (req, res) => {
    if (!cloudReady) {
      return res.status(503).json({ error: "Cloudinary not configured" });
    }
    if (!req.file) {
      return res.status(400).json({ error: "file field required" });
    }

    const inputPath  = req.file.path;
    const outFull    = inputPath + "_full.webp";
    const outThumb   = inputPath + "_thumb.webp";
    const folder     = (req.body && req.body.folder) || "callx/image";

    cloudinary.config({
      cloud_name: CLOUD_NAME,
      api_key:    CLOUD_KEY,
      api_secret: CLOUD_SEC
    });

    try {
      // ── Step 1: Sharp — full image resize + WebP ──────────────────────
      await sharp(inputPath)
        .rotate()                          // EXIF rotation auto-fix
        .resize(FULL_MAX_PX, FULL_MAX_PX, {
          fit: "inside",
          withoutEnlargement: true
        })
        .webp({ quality: FULL_QUALITY })
        .toFile(outFull);

      // ── Step 2: Sharp — thumbnail 200×200 center crop ────────────────
      await sharp(inputPath)
        .rotate()
        .resize(THUMB_SIZE, THUMB_SIZE, {
          fit: "cover",
          position: "centre"
        })
        .webp({ quality: THUMB_QUALITY })
        .toFile(outThumb);

      const compressedBytes = fs.statSync(outFull).size;
      const thumbBytes      = fs.statSync(outThumb).size;

      // ── Step 3: Cloudinary pe full image upload ───────────────────────
      const fullResult = await cloudinary.uploader.upload(outFull, {
        resource_type: "image",
        folder:        folder
      });

      // ── Step 4: Cloudinary pe thumb upload ───────────────────────────
      const thumbResult = await cloudinary.uploader.upload(outThumb, {
        resource_type: "image",
        folder:        "callx/thumb"
      });

      res.json({
        image_url:        fullResult.secure_url,
        thumb_url:        thumbResult.secure_url,
        compressed_bytes: compressedBytes,
        thumb_bytes:      thumbBytes
      });

    } catch (err) {
      console.error("[compress/image] failed:", err.message);
      res.status(500).json({ error: err.message });
    } finally {
      try { if (fs.existsSync(inputPath)) fs.unlinkSync(inputPath); } catch (_) {}
      try { if (fs.existsSync(outFull))   fs.unlinkSync(outFull);   } catch (_) {}
      try { if (fs.existsSync(outThumb))  fs.unlinkSync(outThumb);  } catch (_) {}
    }
  });

  console.log("[OK] /compress/image endpoint ready");
})();

// ══════════════════════════════════════════════════════════════════════════════
// AUDIO MIX — Android v25 server-side audio mixing endpoint
// POST /audio/mix  (multipart/form-data)
//
// Mobile ne video + music URL bheja → server FFmpeg se mix karta hai →
// Cloudinary pe upload karta hai → output URL return karta hai
//
// Fields:
//   video        — video file with mic audio (file)
//   music_url    — background music URL (string, optional)
//   voiceover    — voiceover AAC file (file, optional)
//   mic_vol      — mic audio volume 0.0–1.0
//   music_vol    — music volume 0.0–1.0
//   voiceover_vol — voiceover volume 0.0–1.0
//
// Response: { output_url, public_id }
// ══════════════════════════════════════════════════════════════════════════════
(function setupAudioMix() {
  let multer, cloudinary, ffmpeg, fs, os, path;

  try {
    multer     = require("multer");
    cloudinary = require("cloudinary").v2;
    ffmpeg     = require("fluent-ffmpeg");
    fs         = require("fs");
    os         = require("os");
    path       = require("path");
  } catch (e) {
    console.warn("[WARN] /audio/mix deps missing:", e.message);
    app.post("/audio/mix", (req, res) => {
      res.status(503).json({
        error: "Server audio mix ready nahi hai",
        hint: "npm install multer cloudinary fluent-ffmpeg"
      });
    });
    app.get("/audio/download", (req, res) => {
      res.status(503).json({ error: "Audio mix server ready nahi hai" });
    });
    return;
  }

  const upload = multer({
    dest: os.tmpdir(),
    limits: { fileSize: 500 * 1024 * 1024 }
  });

  app.post("/audio/mix", upload.fields([
    { name: "video",     maxCount: 1 },
    { name: "voiceover", maxCount: 1 }
  ]), async (req, res) => {
    if (!cloudReady) {
      return res.status(503).json({ error: "Cloudinary not configured" });
    }

    const videoFile  = req.files?.["video"]?.[0];
    const voiceFile  = req.files?.["voiceover"]?.[0];

    if (!videoFile) {
      return res.status(400).json({ error: "video field required" });
    }

    const {
      music_url     = "",
      mic_vol       = "1.0",
      music_vol     = "0.5",
      voiceover_vol = "1.0"
    } = req.body;

    const outputPath = path.join(os.tmpdir(), `mixed_${Date.now()}.mp4`);

    cloudinary.config({
      cloud_name: CLOUD_NAME,
      api_key:    CLOUD_KEY,
      api_secret: CLOUD_SEC
    });

    try {
      // ── Build FFmpeg filter_complex ───────────────────────────────────────
      // Input 0: video (with mic audio)
      // Input 1: music URL (optional)
      // Input 2: voiceover file (optional)

      const hasMusicUrl = music_url && music_url.trim().length > 0;
      const hasVoiceover = voiceFile && fs.existsSync(voiceFile.path);

      await new Promise((resolve, reject) => {
        let cmd = ffmpeg(videoFile.path);

        let filterComplex = "";
        let audioOutput   = "";
        let inputCount    = 1;

        // Add music input
        if (hasMusicUrl) {
          cmd = cmd.input(music_url);
          inputCount++;
        }

        // Add voiceover input
        if (hasVoiceover) {
          cmd = cmd.input(voiceFile.path);
          inputCount++;
        }

        // Build filter_complex
        if (!hasMusicUrl && !hasVoiceover) {
          // Sirf mic — volume adjust
          filterComplex = `[0:a]volume=${mic_vol}[out]`;
          audioOutput   = "[out]";

        } else if (hasMusicUrl && !hasVoiceover) {
          // Mic + music
          filterComplex =
            `[0:a]volume=${mic_vol}[mic];` +
            `[1:a]volume=${music_vol}[music];` +
            `[mic][music]amix=inputs=2:duration=first:dropout_transition=2[out]`;
          audioOutput = "[out]";

        } else if (!hasMusicUrl && hasVoiceover) {
          // Mic + voiceover
          filterComplex =
            `[0:a]volume=${mic_vol}[mic];` +
            `[1:a]volume=${voiceover_vol}[vo];` +
            `[mic][vo]amix=inputs=2:duration=first[out]`;
          audioOutput = "[out]";

        } else {
          // Mic + music + voiceover
          filterComplex =
            `[0:a]volume=${mic_vol}[mic];` +
            `[1:a]volume=${music_vol}[music];` +
            `[2:a]volume=${voiceover_vol}[vo];` +
            `[mic][music][vo]amix=inputs=3:duration=first:dropout_transition=2[out]`;
          audioOutput = "[out]";
        }

        cmd
          .complexFilter(filterComplex)
          .outputOptions([
            "-map", "0:v",
            "-map", audioOutput,
            "-c:v", "copy",
            "-c:a", "aac",
            "-shortest",
            "-y"
          ])
          .on("end", resolve)
          .on("error", reject)
          .save(outputPath);
      });

      // ── Upload mixed video to Cloudinary ─────────────────────────────────
      const result = await cloudinary.uploader.upload(outputPath, {
        resource_type: "video",
        folder:        "callx/audio/mixed"
      });

      res.json({
        output_url: result.secure_url,
        public_id:  result.public_id
      });

    } catch (err) {
      console.error("[audio/mix] failed:", err.message);
      res.status(500).json({ error: err.message });
    } finally {
      // Cleanup
      try { if (fs.existsSync(videoFile.path))  fs.unlinkSync(videoFile.path);  } catch (_) {}
      try { if (voiceFile && fs.existsSync(voiceFile.path)) fs.unlinkSync(voiceFile.path); } catch (_) {}
      try { if (fs.existsSync(outputPath)) fs.unlinkSync(outputPath); } catch (_) {}
    }
  });

  // ── /audio/download — agar server ne local path diya ─────────────────────
  // (Cloudinary upload fail hone pe fallback — direct file serve karo)
  app.get("/audio/download", (req, res) => {
    const filePath = req.query.path;
    if (!filePath) return res.status(400).json({ error: "path required" });

    // Security: sirf os.tmpdir() ke andar files allow karo
    const safeBase = os.tmpdir();
    const resolved = require("path").resolve(filePath);
    if (!resolved.startsWith(safeBase)) {
      return res.status(403).json({ error: "Path not allowed" });
    }

    const fs2 = require("fs");
    if (!fs2.existsSync(resolved)) {
      return res.status(404).json({ error: "File not found" });
    }

    res.setHeader("Content-Type", "video/mp4");
    fs2.createReadStream(resolved).pipe(res);
  });

  console.log("[OK] /audio/mix + /audio/download endpoints ready");
})();

// ══════════════════════════════════════════════════════════════════════════════
// Helper: build history JSON from Firebase snapshot
// Returns JSON string: [{"id":"-Nabc123","t":"Hi","ts":1234567890,"me":false}, ...]
// "me":true = message sent BY the notification receiver (their own bubble)
// "id" = the message's real Firebase key — Android needs this to safely
// decrypt E2E ("e2r1:...") text through its idempotent per-message decrypt
// cache (see E2EEncryptionManager#decrypt(envelope, partnerUid, messageId)).
// Without a stable id, the client can't cache the result, and re-decrypting
// the same ciphertext a second time (e.g. when the chat is later opened)
// fails — the Double Ratchet only allows a message's key to be derived once.
// ══════════════════════════════════════════════════════════════════════════════
function getHistoryJson(histSnap, receiverUid) {
  if (!histSnap || !histSnap.exists()) return "";
  const items = [];
  histSnap.forEach(child => {
    const v    = child.val() || {};
    const type = String(v.type || "text");
    let   t    = String(v.text || "");
    const ts   = Number(v.timestamp || 0);
    if (ts === 0) return;
    const sid  = String(v.senderId || v.fromUid || "");
    // NOTE: don't apply the "Message"/"Photo"/etc fallback label to E2E
    // ciphertext here — an "e2r1:..." envelope is never empty, so it never
    // hits this branch anyway, but keep the intent explicit: this fallback
    // is only for genuinely empty text (e.g. media-only messages), not a
    // substitute for decryption.
    if (!t) {
      if      (type === "image") t = "\uD83D\uDCF7 Photo";
      else if (type === "video") t = "\uD83C\uDFAC Video";
      else if (type === "audio") t = "\uD83C\uDFA4 Voice message";
      else if (type === "file" ) t = "\uD83D\uDCCE File";
      else if (type === "pdf"  ) t = "\uD83D\uDCC4 PDF document";
      else t = "Message";
    }
    items.push({ id: child.key, t, ts, me: sid === receiverUid });
  });
  items.sort((a, b) => a.ts - b.ts);
  return JSON.stringify(items);
}

// ══════════════════════════════════════════════════════════════════════════════
// Notify single user (v18 — zero Firebase on app side)
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, type, text,
    chatId, messageId, mediaUrl, force,
    // FIX-B: missed_call extra fields (sent by PushNotify.notifyMissedCall)
    callerPhoto = "", callerUid = "", callerName = "",
    isVideo = false, callId = "",
    // Feature-3: missed call count (Android locally tracked — server just passes through to FCM)
    missedCount = "1",
    // Broadcast List: true when this message was fanned out via a broadcast
    // list (BroadcastDeliveryWorker) — passed through so the recipient's
    // notification can show a "📢 Broadcast" indicator, same as WhatsApp.
    broadcast = false,
    // Emoji Reaction (1:1 + group, background/killed-safe) — sent by
    // PushNotify.notifyMessageReaction() / notifyGroupMessageReaction().
    // groupId/groupName only present for group_message_reaction; this push
    // still targets a single toUid (the reacted-to message's author), not
    // a group fan-out — see /notify/group for the fan-out pattern.
    reaction = "", groupId = "", groupName = ""
  } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });

  const isCall           = (type === "call" || type === "video_call");
  const isSpecialRequest  = (type === "special_request");
  const isUnblockNotify  = (type === "unblock_notify");
  const isStatusReply = (type === "status_reply");
  const isMissedCall  = (type === "call_missed" || type === "missed_call"); // FIX-A: PushNotify sends "missed_call", legacy was "call_missed"
  const isViewOnceViewed = (type === "view_once_viewed"); // View Once: silent push to sender when receiver opens
  const isMessageReaction = (type === "message_reaction" || type === "group_message_reaction");
  const skipBlockChecks   = isStatusReply || isMissedCall || isSpecialRequest || isUnblockNotify || isViewOnceViewed;

  // DEBUG: reaction notifications going missing? Check server logs for this
  // line — if it never appears, the app never even hit /notify with a
  // reaction type (client-side issue). If it appears but the client still
  // shows nothing, the bug is on the Android side (check logcat for
  // "CallxFCM"/"ChatReaction"/"GroupReaction" tags).
  if (isMessageReaction) {
    console.log("[/notify] reaction request:", JSON.stringify({
      type, toUid, fromUid, fromName, chatId, groupId, messageId, reaction
    }));
  }

  try {
    const db = admin.database();

    const MAX_SPECIAL_REQUESTS = 3;
    const SEVEN_DAYS_MS = 7 * 24 * 60 * 60 * 1000;

    const reads = [
      db.ref("users/" + toUid).once("value"),
      fromUid ? db.ref("users/" + fromUid).once("value") : Promise.resolve(null),
      (!force && fromUid && !isCall && !skipBlockChecks)
        ? db.ref("permaBlocked/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),
      (!force && fromUid && !isCall && !skipBlockChecks)
        ? db.ref("blocked/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),
      (!force && fromUid && !isCall && !skipBlockChecks)
        ? db.ref("muted/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null),
      (chatId && !isCall)
        ? db.ref("messages/" + chatId)
            .orderByChild("timestamp").limitToLast(5).once("value")
        : Promise.resolve(null),
      // Special request: attempt count + ts for limit & expire checks
      (isSpecialRequest && fromUid)
        ? db.ref("specialRequests/" + toUid + "/" + fromUid).once("value")
        : Promise.resolve(null)
    ];

    const [receiverSnap, senderSnap, pbSnap, blockedSnap, mutedSnap, histSnap, sreqSnap]
      = await Promise.all(reads);

    const user = receiverSnap ? (receiverSnap.val() || {}) : {};
    if (!user.fcmToken) {
      if (isMessageReaction) console.log("[/notify] reaction dropped — toUid has no fcmToken:", toUid);
      return res.status(404).json({ error: "no token" });
    }

    const myThumb        = String(user.thumbUrl || user.photoUrl || "");
    const isPermaBlocked = !skipBlockChecks && pbSnap && pbSnap.val() === true;
    const isBlocked      = !skipBlockChecks && blockedSnap && blockedSnap.val() === true;

    if (isPermaBlocked) {
      if (isMessageReaction) console.log("[/notify] reaction dropped — permaBlocked:", fromUid, "->", toUid);
      return res.json({ ok: true, dropped: "permaBlocked" });
    }

    // Special request: attempt limit + 7-day expire (server-side safety)
    if (isSpecialRequest && fromUid && sreqSnap) {
      const sreq         = sreqSnap.val() || {};
      const attemptCount = Number(sreq.attemptCount || 0);
      const reqTs        = Number(sreq.ts || 0);

      // 7-day auto-expire — blocker ne respond nahi kiya
      if (reqTs > 0 && (Date.now() - reqTs) > SEVEN_DAYS_MS) {
        await db.ref("permaBlocked/" + toUid + "/" + fromUid).set(true);
        await db.ref("specialRequests/" + toUid + "/" + fromUid).remove();
        await db.ref("seenRequests/" + toUid + "/" + fromUid).remove();
        return res.json({ ok: true, dropped: "expiredRequest" });
      }

      // Attempt limit
      if (attemptCount >= MAX_SPECIAL_REQUESTS) {
        await db.ref("permaBlocked/" + toUid + "/" + fromUid).set(true);
        return res.json({ ok: true, dropped: "maxAttemptsReached" });
      }
    }

    let fromMobile = "", fromPhoto = "", fromThumb = "", fromLastSeen = "0";
    // HUN-FIX: reaction (and other) pushes were showing "Someone" because the
    // Android side sometimes has no reliable in-memory display name at the
    // moment it fires (e.g. ChatReactionController reacting from a chat that
    // was opened via a notification tap, where "currentName" extra isn't
    // always passed through). Server already fetches senderSnap for
    // fromMobile/fromPhoto — reuse it as an authoritative fallback for the
    // name too, so the notification never has to guess.
    let dbFromName = "";
    if (senderSnap) {
      const f   = senderSnap.val() || {};
      fromMobile   = String(f.mobile   || f.callxId || "");
      fromPhoto    = String(f.photoUrl || req.body.fromPhoto || "");
      fromThumb    = String(f.thumbUrl || "");
      fromLastSeen = String(f.lastSeen || 0);
      dbFromName   = String(f.name || f.displayName || "");
    } else if (req.body.fromPhoto) {
      fromPhoto = String(req.body.fromPhoto);
    }
    const finalFromName = (fromName && String(fromName).trim())
      ? String(fromName) : dbFromName;

    // ── Feature-4: Missed call — server se caller ka lastSeen + online fetch karo ──
    // Android side async Firebase fetch karta hai, but server se bhi pass karo
    // taaki killed state mein bhi notification mein lastSeen subText aaye.
    let callerLastSeen = "0";
    let callerOnline   = "false";
    if (isMissedCall && (callerUid || fromUid)) {
      try {
        const callerRef  = callerUid || fromUid;
        // senderSnap already fetched — reuse karo
        const callerData = senderSnap ? (senderSnap.val() || {}) : {};
        callerLastSeen   = String(callerData.lastSeen || 0);
        callerOnline     = String(callerData.online   === true ? "true" : "false");
      } catch (_) {}
    }

    const history = getHistoryJson(histSnap, toUid);
    const isMuted = !skipBlockChecks && mutedSnap && mutedSnap.val() === true;

    const message = {
      token: user.fcmToken,
      data: {
        type:         String(type      || "message"),
        fromUid:      String(fromUid   || ""),
        fromName:     finalFromName,
        fromMobile:   fromMobile,
        fromPhoto:    fromPhoto,
        fromThumb:    fromThumb,
        fromLastSeen: fromLastSeen,
        chatId:       String(chatId    || ""),
        messageId:    String(messageId || ""),
        // FIX: Android's CallxMessagingService reads "msgId" (this is what
        // group message notifications already send, alongside "messageId" —
        // see the /group notify path below). The 1:1 /notify path was only
        // sending "messageId", so the client's msgId lookup always fell
        // through to its generated fallback — meaning the real Firebase
        // message id never reached the client for this message. That broke
        // E2EEncryptionManager's per-messageId decrypt cache: a message
        // "decrypted" by the notification (uncached, since it had no real
        // id to key against) would fail with "Unable to decrypt message"
        // when ChatActivity decrypted the SAME ciphertext again later using
        // its real id — the Double Ratchet only allows a message's key to
        // be derived once. Sending msgId here keeps both paths on the same
        // real id so the cache actually works.
        msgId:        String(messageId || ""),
        mediaUrl:     String(mediaUrl  || ""),
        text:         String(text      || ""),
        permaBlocked: "0",
        blocked:      isBlocked ? "1" : "0",
        muted:        isMuted   ? "1" : "0",
        history:      history,
        myThumb:      myThumb,
        broadcast:    (broadcast === true || broadcast === "true") ? "1" : "0",
        // Emoji Reaction passthrough — see PushNotify.notifyMessageReaction()
        // / notifyGroupMessageReaction(). groupId/groupName are only set for
        // group_message_reaction (message_reaction leaves them "").
        ...(isMessageReaction ? {
          reaction:  String(reaction  || "❤️"),
          groupId:   String(groupId   || ""),
          groupName: String(groupName || ""),
          // HUN-FIX: reaction time, so Android can setWhen() on the
          // notification and show a real timestamp instead of "now".
          ts:        String(Date.now())
        } : {}),
        ...(isCall && text ? { callId: String(text) } : {}),
        // FIX-B: missed_call fields — client reads callerPhoto/callerUid/callerName/isVideo
        ...(isMissedCall ? {
          callerPhoto:    String(callerPhoto || fromPhoto || ""),
          callerUid:      String(callerUid   || fromUid   || ""),
          callerName:     String(callerName  || fromName  || ""),
          isVideo:        String(isVideo === true || isVideo === "true"),
          callId:         String(callId || ""),
          // Feature-3: grouping count — Android SharedPrefs se track hota hai,
          // server just passes through for multi-device / reinstall scenarios
          missedCount:    String(missedCount || "1"),
          // Feature-4: lastSeen — Android notification subText mein dikhta hai
          // "Last seen 5 min ago" / "Online now"
          callerLastSeen: callerLastSeen,
          callerOnline:   callerOnline
        } : {})
      },
      android: {
        priority: (isMuted && !isCall && !isStatusReply) ? "normal"
                : isViewOnceViewed ? "normal"   // silent — no wake lock needed
                : "high",
        ...(isCall ? { ttl: 30000 } : {}),
        ...(isMissedCall ? { ttl: 86400000 } : {}),  // FIX-B: missed call 24h TTL
        ...(isViewOnceViewed ? { ttl: 3600000 } : {})  // view_once_viewed: 1h TTL
      }
    };

    const r = await admin.messaging().send(message);
    if (isMessageReaction) console.log("[/notify] reaction FCM sent OK, id:", r, "toUid:", toUid);
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify reel like / comment / following-posted etc. (v14)
// ══════════════════════════════════════════════════════════════════════════════
const VALID_REEL_TYPES = new Set([
  "like", "comment", "comment_like", "comment_reply",
  "mention_caption", "mention_comment", "new_follower",
  "following_posted", "duet", "stitch", "video_reply",
  "collab_request", "collab_accepted", "collab_declined", "gift",
  "live_started", "live_milestone", "close_friend_live",
  "trending", "viral", "view_milestone", "follower_milestone",
  "upload_complete", "upload_failed", "scheduled_post",
  "scheduled_reminder", "product_tag_click", "creator_fund_payout",
  "content_removed", "report_resolved", "sound_trending",
  "pinned_comment", "close_friend_post", "challenge",
  "reel_shared", "reel_saved", "reel_downloaded",
  "weekly_digest", "collab_live",
  // Feature-3 (missed call grouping) se related nahi — ye repost notify fix hai:
  // PushNotify.notifyReelRepost() type="repost" bhejta hai — pehle 400 error aata tha
  "repost",
  // Multi-Duet invite
  "multi_duet_invite",
  // ✅ FIX: client (ReelFCMNotificationHandler.TYPE_MULTI_DUET_READY) "multi_duet_ready"
  // bhejta hai jab sab participants record kar chuke hote h, par ye set me missing tha
  // — isliye ye push hamesha 400 "invalid reel_notif_type" leke fail ho jaata tha.
  "multi_duet_ready",
  // Collab Repost cross-device push
  "collab_repost_invite",
  "collab_repost_accepted",
  "collab_repost_declined"
]);

app.post("/notify/reel", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, fromPhoto,
    reelId, reelThumb, type, commentText, commentId,
    sessionId,      // multi_duet_invite ke liye extra field
    collabRepostId, // Collab Repost: invite/accepted/declined ke liye (CollabRepostNotificationHelper isi key se padhta h)
    newReelId,      // Collab Repost: accepted ke baad ka naya reel id
    collabId        // NEW — "Add Collaborators" (joint-post) feature: collabPostInvites/
                     // ki push key. ReelFCMNotificationHandler ise "collab_id" data-key se
                     // padhta hai (TYPE_COLLAB_REQUEST / TYPE_COLLAB_ACCEPTED / TYPE_COLLAB_DECLINED)
                     // — pehle ye field yahan accept hi nahi hoti thi isliye collab_id hamesha
                     // khali jaata tha aur notification tap karne par sahi invite open nahi hota tha.
  } = req.body || {};

  if (!toUid)  return res.status(400).json({ error: "toUid required" });
  if (!type)   return res.status(400).json({ error: "type required" });
  if (!VALID_REEL_TYPES.has(type))
    return res.status(400).json({ error: "invalid reel_notif_type: " + type });

  const noReelIdNeeded = ["new_follower", "weekly_digest", "follower_milestone",
    "creator_fund_payout", "report_resolved", "upload_failed"];
  if (!noReelIdNeeded.includes(type) && !reelId)
    return res.status(400).json({ error: "reelId required for type: " + type });

  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const snap = await admin.database().ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken) {
      // 🐞 BUG FIX: this returned 404 with zero server-side log — so a
      // missing/stale fcmToken for the recipient (e.g. collab invite target)
      // silently dropped the push with no trace anywhere. Now it's logged
      // so "collab_request never arrived" is diagnosable from server logs.
      console.warn("[/notify/reel] no fcmToken for toUid=" + toUid + " type=" + type + " reelId=" + (reelId || ""));
      return res.status(404).json({ error: "no token" });
    }

    let senderPhoto = String(fromPhoto || "");
    if (!senderPhoto && fromUid) {
      try {
        const fSnap = await admin.database().ref("users/" + fromUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    const r = await admin.messaging().send({
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
        session_id:      String(sessionId   || ""),  // multi_duet_invite
        // Collab Repost — keys camelCase rakhi h kyunki ReelFCMNotificationHandler
        // exactly "collabRepostId" / "newReelId" string se hi padhta h (get(data, "collabRepostId")).
        // Pehle ye fields yaha forward nahi ho rahe the, isliye collab repost push
        // aa to jaati thi but collabId/newReelId empty aate the (notif id clash +
        // "highlight_collab_id" deep-link kabhi kaam nahi karta tha).
        collabRepostId:  String(collabRepostId || ""),
        newReelId:       String(newReelId      || ""),
        // NEW — "Add Collaborators" (joint-post) feature. Key "collab_id" rakhi h kyunki
        // ReelFCMNotificationHandler exactly isi string se padhta h (get(data, "collab_id")).
        collab_id:       String(collabId || "")
      },
      android: { priority: "high", ttl: 86400000 }
    });

    // 🐞 BUG FIX: no success log existed for collab pushes — add one so a
    // successful send is confirmable in server logs (e.g. Render dashboard)
    // and clearly distinguished from client-side delivery/display failures.
    if (type === "collab_request" || type === "collab_accepted" || type === "collab_declined") {
      console.log("[/notify/reel] " + type + " sent OK, id:", r, "toUid:", toUid, "reelId:", reelId);
    }

    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("reel notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify X feature — like, retweet, reply, mention, quote, follow, dm,
//                    poll_ended, list_added, space_started, close_friend_post
// POST /notify/x
//
// Body keys:
//   toUid          — receiver UID (required)
//   fromUid        — sender UID
//   fromName       — sender display name
//   fromHandle     — sender @handle (optional — server x/users se auto-fetch karta hai)
//   fromPhoto      — sender avatar URL (optional — server users/ se auto-fetch karta hai)
//   type           — x_notif_type value (required)
//   tweetId        — target tweet ID (like/retweet/reply/mention/quote)
//   conversationId — DM conversation ID
//   otherUid       — DM other user UID
//   otherHandle    — DM other user handle
//   otherPhoto     — DM other user avatar URL
//   preview        — DM message preview text
//   pollQuestion   — poll_ended ke liye poll question
//   listName       — list_added ke liye list name
//   spaceId        — space_started ke liye space ID
//   spaceTitle     — space_started ke liye space title
//
// Android client: XFCMNotificationHandler.handle() routes by "x_notif_type" key
// ══════════════════════════════════════════════════════════════════════════════
const VALID_X_TYPES = new Set([
  "like", "retweet", "reply", "mention", "quote", "follow", "dm",
  "poll_ended", "list_added", "space_started", "close_friend_post"
]);

app.post("/notify/x", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, fromPhoto,
    fromHandle     = "",
    type,
    tweetId        = "",
    conversationId = "",
    otherUid       = "",
    otherHandle    = "",
    otherPhoto     = "",
    preview        = "",
    pollQuestion   = "",
    listName       = "",
    spaceId        = "",
    spaceTitle     = ""
  } = req.body || {};

  if (!toUid) return res.status(400).json({ error: "toUid required" });
  if (!type)  return res.status(400).json({ error: "type required" });
  if (!VALID_X_TYPES.has(type))
    return res.status(400).json({ error: "invalid x_notif_type: " + type });

  // Self-notification drop
  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    // Sender photo + handle fallback — Firebase se fetch karo agar nahi mila
    let senderPhoto  = String(fromPhoto  || "");
    let senderHandle = String(fromHandle || "");
    if (fromUid && (!senderPhoto || !senderHandle)) {
      try {
        // x/users me X-specific profile hai — photo aur handle dono yahan se lo
        const xSnap = await db.ref("x/users/" + fromUid).once("value");
        const xVal  = xSnap.val() || {};
        if (!senderPhoto)  senderPhoto  = String(xVal.thumbUrl || xVal.photoUrl || "");
        if (!senderHandle) senderHandle = String(xVal.handle   || xVal.username  || "");
        // x/users me photo nahi mila to main users/ se try karo
        if (!senderPhoto) {
          const fSnap = await db.ref("users/" + fromUid).once("value");
          const fVal  = fSnap.val() || {};
          senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
        }
      } catch (_) {}
    }
    // TTL: 4h for all X notifications (background/killed safe)
    const ttlMs = 4 * 60 * 60 * 1000;

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        x_notif_type:   String(type),
        fromUid:        String(fromUid        || ""),
        fromName:       String(fromName        || ""),
        fromHandle:     senderHandle,
        fromPhoto:      senderPhoto,
        tweetId:        String(tweetId        || ""),
        conversationId: String(conversationId || ""),
        otherUid:       String(otherUid       || ""),
        otherHandle:    String(otherHandle    || ""),
        otherPhoto:     String(otherPhoto     || ""),
        preview:        String(preview        || ""),
        pollQuestion:   String(pollQuestion   || ""),
        listName:       String(listName       || ""),
        spaceId:        String(spaceId        || ""),
        spaceTitle:     String(spaceTitle     || "")
      },
      android: { priority: "high", ttl: ttlMs }
    });

    // Firebase DB me bhi save karo — XNotificationWorker background polling ke liye
    // x/notifications/{toUid}/{pushKey} — read: false, notified: false
    try {
      await db.ref("x/notifications/" + toUid).push({
        type:           String(type),
        fromUid:        String(fromUid        || ""),
        fromName:       String(fromName        || ""),
        fromHandle:     senderHandle,
        fromPhotoUrl:   senderPhoto,
        tweetId:        String(tweetId        || ""),
        conversationId: String(conversationId || ""),
        otherUid:       String(otherUid       || ""),
        otherHandle:    String(otherHandle    || ""),
        otherPhotoUrl:  String(otherPhoto     || ""),
        preview:        String(preview        || ""),
        pollQuestion:   String(pollQuestion   || ""),
        listName:       String(listName       || ""),
        spaceId:        String(spaceId        || ""),
        spaceTitle:     String(spaceTitle     || ""),
        read:           false,
        notified:       false,
        timestamp:      Date.now()
      });
    } catch (_) {} // DB save fail hone se FCM response affect na ho

    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("x notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// POST /notify/youtube
// YouTube notification — background/killed state safe via FCM + Firebase DB save
//
// Body keys:
//   toUid        — receiver UID (required)
//   fromUid      — sender / channel UID
//   fromName     — channel / commenter display name
//   fromPhoto    — avatar URL (optional — server youtube/channels/ se auto-fetch)
//   type         — yt_notif_type value (required)
//   videoId      — target video ID
//   videoTitle   — video title
//   thumbnailUrl — video thumbnail URL
//   commentText  — comment / reply preview text
//   likeCount    — like_milestone ke liye
//
// Android: CallxMessagingService → YouTubeFCMNotificationHandler.handle()
// ══════════════════════════════════════════════════════════════════════════════
const VALID_YT_TYPES = new Set([
  "new_video", "comment", "reply", "subscribe", "live", "like_milestone"
]);

app.post("/notify/youtube", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName, fromPhoto,
    type,
    videoId        = "",
    videoTitle     = "",
    thumbnailUrl   = "",
    commentText    = "",
    likeCount      = ""
  } = req.body || {};

  if (!toUid) return res.status(400).json({ error: "toUid required" });
  if (!type)  return res.status(400).json({ error: "type required" });
  if (!VALID_YT_TYPES.has(type))
    return res.status(400).json({ error: "invalid yt_notif_type: " + type });

  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken)
      return res.status(404).json({ error: "no token" });

    // Sender photo fallback — youtube/channels/ se fetch karo
    let senderPhoto = String(fromPhoto || "");
    if (fromUid && !senderPhoto) {
      try {
        const chSnap = await db.ref("youtube/channels/" + fromUid).once("value");
        const chVal  = chSnap.val() || {};
        senderPhoto = String(chVal.thumbUrl || chVal.photoUrl || chVal.avatarUrl || "");
        // Fallback to main users/
        if (!senderPhoto) {
          const uSnap = await db.ref("users/" + fromUid).once("value");
          const uVal  = uSnap.val() || {};
          senderPhoto = String(uVal.thumbUrl || uVal.photoUrl || "");
        }
      } catch (_) {}
    }

    // TTL: all YouTube notifications = 4h (background/killed safe)
    // 60s was too short — Doze mode pe expire ho jaata tha before delivery
    const ttlMs = 4 * 60 * 60 * 1000;

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        yt_notif_type:  String(type),
        fromUid:        String(fromUid       || ""),
        fromName:       String(fromName      || ""),
        fromPhoto:      senderPhoto,
        videoId:        String(videoId       || ""),
        videoTitle:     String(videoTitle    || ""),
        thumbnailUrl:   String(thumbnailUrl  || ""),
        commentText:    String(commentText   || ""),
        likeCount:      String(likeCount     || "")
      },
      android: { priority: "high", ttl: ttlMs }
    });

    // Firebase DB me bhi save — YouTubeNotificationWorker background polling ke liye
    try {
      await db.ref("youtube/notifications/" + toUid).push({
        type:         String(type),
        fromUid:      String(fromUid       || ""),
        fromName:     String(fromName      || ""),
        fromPhotoUrl: senderPhoto,
        videoId:      String(videoId       || ""),
        videoTitle:   String(videoTitle    || ""),
        thumbnailUrl: String(thumbnailUrl  || ""),
        commentText:  String(commentText   || ""),
        likeCount:    String(likeCount     || ""),
        notified:     false,
        read:         false,
        timestamp:    Date.now()
      });
    } catch (_) {}

    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("youtube notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify group (production-grade fanout v2)
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/group", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    groupId, fromUid, fromName, fromPhoto,
    messageId, type, text, mediaUrl
  } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });

  try {
    const db    = admin.database();
    const gSnap = await db.ref("groups/" + groupId).once("value");
    const g     = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const groupName  = String(g.name    || "Group");
    const groupIcon  = String(g.iconUrl || "");
    const memberUids = Object.keys(g.members || {}).filter(uid => uid !== fromUid);
    const mutedBy    = g.mutedBy || {};

    const sharedReads = [
      fromUid ? db.ref("users/" + fromUid).once("value") : Promise.resolve(null),
      db.ref("messages/" + groupId)
        .orderByChild("timestamp").limitToLast(5).once("value")
    ];
    const pbReads    = memberUids.map(uid =>
      fromUid ? db.ref("permaBlocked/" + uid + "/" + fromUid).once("value")
              : Promise.resolve(null));
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
      senderMobile   = String(f.mobile || f.callxId || "");
      senderLastSeen = String(f.lastSeen || 0);
    }

    const history = getHistoryJson(histSnap, null);

    // @mention detection
    const mentionedUids = new Set();
    if (text) {
      const lower = text.toLowerCase();
      if (lower.includes("@everyone") || lower.includes("@all")) {
        memberUids.forEach(uid => mentionedUids.add(uid));
      } else {
        const mentionTokens = (text.match(/@(\w+)/g) || [])
          .map(t => t.slice(1).toLowerCase());
        if (mentionTokens.length > 0) {
          tokenResults.forEach((snap, idx) => {
            if (!snap) return;
            const u    = snap.val() || {};
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
        const pbSnap = pbResults[idx];
        if (pbSnap && pbSnap.val() === true) { dropped++; return; }

        const u  = tokenResults[idx] ? (tokenResults[idx].val() || {}) : {};
        const tk = u.fcmToken;
        if (!tk) { dropped++; return; }

        const isMuted = mutedBy[uid] === true;
        updates["groups/" + groupId + "/unread/" + uid] =
          admin.database.ServerValue.increment(1);

        await admin.messaging().send({
          token: tk,
          data: {
            type:         String(type || "group_message"),
            groupId:      String(groupId),
            groupName:    groupName,
            groupIcon:    groupIcon,
            fromUid:      String(fromUid   || ""),
            fromName:     String(fromName  || ""),
            fromPhoto:    senderPhoto,
            fromThumb:    senderPhoto,
            fromMobile:   senderMobile,
            fromLastSeen: senderLastSeen,
            messageId:    String(messageId || ""),
            msgId:        String(messageId || ""),
            mediaUrl:     String(mediaUrl  || ""),
            text:         String(text      || ""),
            muted:        isMuted ? "1" : "0",
            mention:      mentionedUids.has(uid) ? "true" : "false",
            priority:     "false",
            history:      history
          },
          android: {
            priority:    isMuted ? "normal" : "high",
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

    try { if (Object.keys(updates).length) await db.ref().update(updates); } catch (_) {}
    try { for (const uid of staleTokens) await db.ref("users/" + uid + "/fcmToken").remove(); } catch (_) {}

    res.json({ ok: true, sent, dropped, members: memberUids.length });
  } catch (e) {
    console.error("group notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Reset unread counter for a group
// ══════════════════════════════════════════════════════════════════════════════
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

// ══════════════════════════════════════════════════════════════════════════════
// Notify status (fanout to all contacts of poster) — rich payload v3
// Sends: fromPhoto, statusType, text, mediaUrl so receiver can show
// BigPicture notification even in killed state.
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/status", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    fromUid, fromName,
    fromPhoto  = "",
    statusType = "text",
    text       = "",
    mediaUrl   = ""
  } = req.body || {};
  if (!fromUid) return res.status(400).json({ error: "fromUid required" });

  try {
    const db    = admin.database();
    const cSnap = await db.ref("contacts/" + fromUid).once("value");
    const uids  = Object.keys(cSnap.val() || {});
    if (!uids.length) return res.json({ ok: true, sent: 0 });

    // Fetch sender photo fallback
    let senderPhoto = String(fromPhoto || "");
    if (!senderPhoto) {
      try {
        const fSnap = await db.ref("users/" + fromUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    // Batch fetch all user tokens
    const userSnaps = await Promise.all(
      uids.map(uid => db.ref("users/" + uid).once("value"))
    );

    let sent = 0;
    await Promise.all(userSnaps.map(async (snap) => {
      const u  = snap.val() || {};
      const tk = u.fcmToken;
      if (!tk) return;
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:       "status",
            fromUid:    String(fromUid),
            fromName:   String(fromName  || "Friend"),
            fromPhoto:  senderPhoto,
            statusType: String(statusType),
            text:       String(text),
            mediaUrl:   String(mediaUrl)
          },
          android: { priority: "high" }
        });
        sent++;
      } catch (e) {
        console.warn("status send fail:", e.message);
      }
    }));

    res.json({ ok: true, sent });
  } catch (e) {
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify status reaction — POST /notify/status_reaction
// Called by PushNotify.notifyStatusReaction()
// Payload: toUid, fromUid, fromName, fromPhoto, reaction, ownerUid
// Android: CallxMessagingService → handleStatusReaction()
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/status_reaction", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, fromUid, fromName,
    fromPhoto = "",
    reaction  = "❤️",
    ownerUid  = ""
  } = req.body || {};
  if (!toUid) return res.status(400).json({ error: "toUid required" });
  if (toUid === fromUid) return res.json({ ok: true, dropped: "self" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken) return res.status(404).json({ error: "no token" });

    let senderPhoto = String(fromPhoto || "");
    if (!senderPhoto && fromUid) {
      try {
        const fSnap = await db.ref("users/" + fromUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        type:      "status_reaction",
        fromUid:   String(fromUid   || ""),
        fromName:  String(fromName  || ""),
        fromPhoto: senderPhoto,
        reaction:  String(reaction),
        ownerUid:  String(ownerUid)
      },
      android: { priority: "high", ttl: 6 * 60 * 60 * 1000 }
    });
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("status_reaction notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify broadcast delivery — POST /notify/broadcast
// Called by PushNotify.notifyBroadcastComplete() from BroadcastDeliveryWorker
// after a broadcast list fan-out finishes (success OR failure).
//
// Purpose: sender-side, background/killed-safe confirmation push.
//   • BroadcastDeliveryWorker runs via WorkManager and already shows a LOCAL
//     notification directly on the sending device the instant it finishes —
//     that covers the common case with zero network round-trip.
//   • This endpoint additionally pushes an FCM "broadcast_message" data
//     message to the sender's account so that ANY other signed-in device
//     (tablet, secondary phone) also gets the delivery summary even if that
//     device was in the background or fully killed — same high-priority
//     data-only pattern already used for reel/x/youtube/status notifications.
//
// Payload: toUid (sender uid), listId, listName, delivered, total, skipped,
//          status ("sent"|"failed"), msgType, lastMessage
// Android: type="broadcast_message" → CallxMessagingService →
//          BroadcastFCMHandler.handle() → opens BroadcastChatActivity
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/broadcast", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const {
    toUid, listId, listName = "Broadcast",
    delivered = 0, total = 0, skipped = 0,
    status = "sent", msgType = "text", lastMessage = ""
  } = req.body || {};
  if (!toUid)  return res.status(400).json({ error: "toUid required" });
  if (!listId) return res.status(400).json({ error: "listId required" });

  try {
    const db   = admin.database();
    const snap = await db.ref("users/" + toUid).once("value");
    const user = snap.val() || {};
    if (!user.fcmToken) return res.status(404).json({ error: "no token" });

    const r = await admin.messaging().send({
      token: user.fcmToken,
      data: {
        type:        "broadcast_message",
        list_id:     String(listId),
        list_name:   String(listName),
        delivered:   String(delivered),
        total:       String(total),
        skipped:     String(skipped),
        status:      String(status),
        msg_type:    String(msgType),
        last_message:String(lastMessage || "")
      },
      // Self-notify, background/killed-safe: high priority so FCM wakes the
      // app to post the notification even if the process was killed, with a
      // generous TTL since it's an informational summary, not time-critical.
      android: { priority: "high", ttl: 24 * 60 * 60 * 1000 }
    });
    res.json({ ok: true, id: r });
  } catch (e) {
    console.error("notify/broadcast err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify contact join — POST /notify/contact_join
// Called by PushNotify.notifyContactsOfNewUser()
// Fanout: notifies all contacts of newUid that they joined CallX
// Payload: newUid, newName, newPhoto
// Android: type="contact_join" → CallxMessagingService
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/contact_join", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const { newUid, newName, newPhoto = "" } = req.body || {};
  if (!newUid) return res.status(400).json({ error: "newUid required" });

  try {
    const db    = admin.database();
    const cSnap = await db.ref("contacts/" + newUid).once("value");
    const uids  = Object.keys(cSnap.val() || {});
    if (!uids.length) return res.json({ ok: true, sent: 0 });

    let senderPhoto = String(newPhoto || "");
    if (!senderPhoto) {
      try {
        const fSnap = await db.ref("users/" + newUid).once("value");
        const fVal  = fSnap.val() || {};
        senderPhoto = String(fVal.thumbUrl || fVal.photoUrl || "");
      } catch (_) {}
    }

    const userSnaps = await Promise.all(
      uids.map(uid => db.ref("users/" + uid).once("value"))
    );

    let sent = 0;
    await Promise.all(userSnaps.map(async (snap) => {
      const u  = snap.val() || {};
      const tk = u.fcmToken;
      if (!tk) return;
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:     "contact_join",
            fromUid:  String(newUid),
            fromName: String(newName  || ""),
            fromPhoto: senderPhoto
          },
          android: { priority: "normal", ttl: 24 * 60 * 60 * 1000 }
        });
        sent++;
      } catch (e) {
        console.warn("contact_join send fail:", e.message);
      }
    }));

    res.json({ ok: true, sent });
  } catch (e) {
    console.error("contact_join notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Notify group member joined — POST /notify/group_join
// Called by PushNotify.notifyGroupMemberJoined()
// Fanout: notifies all existing group members that someone new joined
// Payload: groupId, groupName, newMemberName
// Android: type="group_member_joined" → CallxMessagingService
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/group_join", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const { groupId, groupName = "Group", newMemberName = "" } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });

  try {
    const db    = admin.database();
    const gSnap = await db.ref("groups/" + groupId).once("value");
    const g     = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const memberUids = Object.keys(g.members || {});
    if (!memberUids.length) return res.json({ ok: true, sent: 0 });

    const userSnaps = await Promise.all(
      memberUids.map(uid => db.ref("users/" + uid).once("value"))
    );

    let sent = 0;
    await Promise.all(userSnaps.map(async (snap) => {
      const u  = snap.val() || {};
      const tk = u.fcmToken;
      if (!tk) return;
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:          "group_member_joined",
            groupId:       String(groupId),
            groupName:     String(groupName),
            newMemberName: String(newMemberName)
          },
          android: { priority: "normal", ttl: 6 * 60 * 60 * 1000 }
        });
        sent++;
      } catch (e) {
        console.warn("group_join send fail:", e.message);
      }
    }));

    res.json({ ok: true, sent });
  } catch (e) {
    console.error("group_join notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// GROUP E2E ENCRYPTION — Sender Keys re-sync nudge — POST /notify/group_key_rotate
//
// Supports GroupE2EManager.java (group chat text — Sender Keys protocol).
// This endpoint carries NO key material at all — Sender Keys are only ever
// exchanged client-to-client, sealed over each pair's existing 1:1
// X3DH/Double-Ratchet session, and dropped at
// groupSenderKeys/{groupId}/{recipientUid}/{fromUid} (a Firebase path this
// server never reads or writes). All this does is send a silent data-only
// push so the remaining members' apps call GroupE2EManager#ensureGroupCrypto
// right away — picking up a rotation (member removed/left) or a fresh
// distribution (member added) sooner than waiting for that member to next
// open the group's chat screen, which is the correctness fallback either way.
//
// Called by GroupInfoActivity right after a member is removed or leaves.
// Payload: groupId, excludeUid (the member who was just removed/left — skip
// notifying them, they no longer have a session worth re-syncing).
// Android: type="group_key_resync" → CallxMessagingService should call
// GroupE2EManager.getInstance(ctx).ensureGroupCrypto(groupId, myUid, null)
// on receipt (data-only push, no visible notification).
// ══════════════════════════════════════════════════════════════════════════════
app.post("/notify/group_key_rotate", async (req, res) => {
  if (!firebaseReady)
    return res.status(503).json({ error: "Firebase not configured" });

  const { groupId, excludeUid = "" } = req.body || {};
  if (!groupId) return res.status(400).json({ error: "groupId required" });

  try {
    const db    = admin.database();
    const gSnap = await db.ref("groups/" + groupId).once("value");
    const g     = gSnap.val();
    if (!g) return res.status(404).json({ error: "group not found" });

    const memberUids = Object.keys(g.members || {}).filter(uid => uid !== excludeUid);
    if (!memberUids.length) return res.json({ ok: true, sent: 0 });

    const userSnaps = await Promise.all(
      memberUids.map(uid => db.ref("users/" + uid).once("value"))
    );

    let sent = 0;
    await Promise.all(userSnaps.map(async (snap) => {
      const u  = snap.val() || {};
      const tk = u.fcmToken;
      if (!tk) return;
      try {
        await admin.messaging().send({
          token: tk,
          data: {
            type:    "group_key_resync",
            groupId: String(groupId)
          },
          android: { priority: "high", ttl: 60 * 60 * 1000 } // no visible notif — data-only, short TTL is fine, next chat-open self-heals anyway
        });
        sent++;
      } catch (e) {
        console.warn("group_key_rotate send fail:", e.message);
      }
    }));

    res.json({ ok: true, sent });
  } catch (e) {
    console.error("group_key_rotate notify err:", e.message);
    res.status(500).json({ error: e.message });
  }
});

// ══════════════════════════════════════════════════════════════════════════════
// Android App Links + Deep Link Routes
// ══════════════════════════════════════════════════════════════════════════════
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

// ══════════════════════════════════════════════════════════════════════════════
// E2E ENCRYPTION — prekey bundle storage & exchange (X3DH key material)
//
// Supports E2EEncryptionManager.java (1:1 chat text — Double Ratchet).
// Firebase path owned exclusively by this server (NOT written/read directly
// by the Android client — see UPGRADE_NOTES_v?_E2EEncryption.md for the
// recommended security-rules change locking this node down):
//
//   e2e_prekeys/{uid} = {
//     identityKey:      base64 EC P-256 public key (long-term identity),
//     signedPreKey:     base64 EC P-256 public key (rotates occasionally),
//     signedPreKeySig:  base64 ECDSA signature of signedPreKey by identityKey,
//     signedPreKeyId:   short id, so a responder can tell if its own SPK
//                       rotated between when a sender fetched it and when
//                       the sender's first message arrives,
//     oneTimePreKeys:   { <id>: base64 pubkey, ... }  — each one handed out
//                       AT MOST ONCE (see the transaction in GET /bundle)
//   }
//
// WHY THIS LIVES ON THE SERVER AND NOT AS A DIRECT CLIENT WRITE (like the
// old e2e_keys/{uid}/publicKey field from the previous, static-key version
// of E2EEncryptionManager): one-time prekeys are only secure if each one is
// ever handed out to exactly one requester. Doing that "pop one and delete
// it" step as a client-side Firebase transaction would require security
// rules that let ANY authenticated user run a transaction against ANY OTHER
// user's prekey node — which is exactly the kind of broad write access you
// don't want on key material. Routing it through the server means the
// Firebase rule for e2e_prekeys can be locked to admin-only (".write":
// false, ".read": false) and every consumption goes through one auditable,
// atomic code path.
// ══════════════════════════════════════════════════════════════════════════════

const MAX_ONE_TIME_PREKEYS_STORED = 100; // cap per user, prevents unbounded growth from repeat uploads

/** Verifies the Firebase ID token on Authorization: Bearer <token> and sets req.uid. */
async function verifyFirebaseAuth(req, res, next) {
  if (!firebaseReady) return res.status(503).json({ error: "Firebase not configured" });
  const authHeader = req.headers.authorization || "";
  const match = authHeader.match(/^Bearer (.+)$/);
  if (!match) return res.status(401).json({ error: "Missing Authorization: Bearer <idToken>" });
  try {
    const decoded = await admin.auth().verifyIdToken(match[1]);
    req.uid = decoded.uid;
    next();
  } catch (e) {
    return res.status(401).json({ error: "Invalid or expired auth token" });
  }
}

// POST /e2e/keys — upload/refresh our own prekey bundle.
// Body: { identityKey, signedPreKey, signedPreKeySig, signedPreKeyId, oneTimePreKeys: [{id,key}, ...] }
// Auth: Authorization: Bearer <Firebase ID token> — req.uid is whose bundle this is (never trusts a uid in the body).
app.post("/e2e/keys", verifyFirebaseAuth, async (req, res) => {
  try {
    const { identityKey, signedPreKey, signedPreKeySig, signedPreKeyId, oneTimePreKeys } = req.body || {};
    if (!identityKey || !signedPreKey || !signedPreKeySig || !signedPreKeyId) {
      return res.status(400).json({
        error: "identityKey, signedPreKey, signedPreKeySig, signedPreKeyId are required"
      });
    }

    const db  = admin.database();
    const ref = db.ref("e2e_prekeys/" + req.uid);

    await ref.update({
      identityKey, signedPreKey, signedPreKeySig, signedPreKeyId,
      updatedAt: admin.database.ServerValue.TIMESTAMP
    });

    if (Array.isArray(oneTimePreKeys) && oneTimePreKeys.length) {
      // Merge new one-time prekeys in rather than clobbering — a partner
      // may be mid-handshake using one from a previous upload right now.
      const otpRef = ref.child("oneTimePreKeys");
      const snap = await otpRef.once("value");
      const merged = Object.assign({}, snap.val() || {});
      for (const otp of oneTimePreKeys) {
        if (otp && otp.id && otp.key) merged[otp.id] = otp.key;
      }
      const ids = Object.keys(merged);
      if (ids.length > MAX_ONE_TIME_PREKEYS_STORED) {
        const dropCount = ids.length - MAX_ONE_TIME_PREKEYS_STORED;
        for (let i = 0; i < dropCount; i++) delete merged[ids[i]]; // drop oldest-inserted first
      }
      await otpRef.set(merged);
    }

    res.json({ ok: true });
  } catch (e) {
    console.error("[/e2e/keys] failed:", e.message);
    res.status(500).json({ error: "internal error" });
  }
});

// GET /e2e/bundle/:uid — fetch a prekey bundle to start an X3DH handshake
// with :uid. Atomically pops (removes) ONE one-time prekey so it can never
// be handed out twice, even under concurrent requests from two different
// partners starting a chat with the same person at once.
// Auth: Authorization: Bearer <Firebase ID token> of the FETCHER (not :uid).
app.get("/e2e/bundle/:uid", verifyFirebaseAuth, async (req, res) => {
  try {
    const targetUid = req.params.uid;
    const db  = admin.database();
    const ref = db.ref("e2e_prekeys/" + targetUid);

    const snap = await ref.once("value");
    if (!snap.exists()) {
      return res.status(404).json({ error: "No prekey bundle published for this user yet" });
    }
    const bundle = snap.val();
    if (!bundle.identityKey || !bundle.signedPreKey || !bundle.signedPreKeySig) {
      return res.status(404).json({ error: "Incomplete prekey bundle" });
    }

    let poppedOneTimePreKey = null;
    const otpRef = ref.child("oneTimePreKeys");
    await otpRef.transaction((current) => {
      poppedOneTimePreKey = null; // reset on every attempt — transaction() may retry this fn on contention
      if (!current) return current;
      const ids = Object.keys(current);
      if (!ids.length) return current;
      const pickedId = ids[0];
      poppedOneTimePreKey = { id: pickedId, key: current[pickedId] };
      const next = Object.assign({}, current);
      delete next[pickedId];
      return next;
    });

    res.json({
      identityKey:     bundle.identityKey,
      signedPreKey:    bundle.signedPreKey,
      signedPreKeySig: bundle.signedPreKeySig,
      signedPreKeyId:  bundle.signedPreKeyId,
      oneTimePreKey:   poppedOneTimePreKey // null if the pool is empty — client falls back to no-OPK X3DH
    });
  } catch (e) {
    console.error("[/e2e/bundle] failed:", e.message);
    res.status(500).json({ error: "internal error" });
  }
});

app.get("/.well-known/assetlinks.json", (req, res) => {
  res.setHeader("Content-Type", "application/json");
  res.setHeader("Cache-Control", "public, max-age=3600");
  res.json(ASSET_LINKS);
});
app.get("/assetlinks.json", (req, res) => {
  res.setHeader("Content-Type", "application/json");
  res.json(ASSET_LINKS);
});

// ── Deep Link HTML page helper ──────────────────────────────────────────────
function deepLinkPage(appUrl, webFallbackUrl, title, description) {
  return `<!DOCTYPE html>
<html>
<head>
  <meta charset="utf-8"/>
  <meta name="viewport" content="width=device-width,initial-scale=1"/>
  <title>${title} – CallX</title>
  <style>
    body{font-family:sans-serif;text-align:center;padding:40px;background:#0f0f0f;color:#fff}
    .logo{font-size:2rem;font-weight:bold;color:#25D366;margin-bottom:8px}
    p{color:#aaa;margin-bottom:24px}
    a.btn{display:inline-block;background:#25D366;color:#fff;padding:14px 32px;
          border-radius:30px;text-decoration:none;font-weight:bold;font-size:1rem}
    .sub{color:#666;font-size:0.85rem;margin-top:16px}
  </style>
</head>
<body>
  <div class="logo">CallX</div>
  <p>${description}</p>
  <a class="btn" id="openBtn" href="${appUrl}">CallX mein kholein</a>
  <p class="sub" id="msg">App khul rahi hai...</p>
  <script>
    var appUrl = "${appUrl}";
    function tryOpen() {
      window.location.href = appUrl;
      setTimeout(function() {
        document.getElementById('msg').innerHTML =
          'App install nahi hai? <a style="color:#25D366" href="https://play.google.com/store/apps/details?id=com.callx.app">Download karo</a>';
      }, 2000);
    }
    window.addEventListener('load', function() { setTimeout(tryOpen, 300); });
    document.getElementById('openBtn').addEventListener('click', function(e) {
      e.preventDefault(); tryOpen();
    });
  </script>
</body>
</html>`;
}

// ── Deep link routes ────────────────────────────────────────────────────────
app.get("/u/:uid",          (req, res) => res.send(deepLinkPage(`callx://u/${req.params.uid}`,            "", "Profile",     "Is user ka profile dekhen CallX app mein")));
app.get("/profile/:uid",    (req, res) => res.send(deepLinkPage(`callx://profile/${req.params.uid}`,      "", "Profile",     "Is user ka profile dekhen CallX app mein")));
app.get("/chat/:uid",       (req, res) => res.send(deepLinkPage(`callx://chat/${req.params.uid}`,         "", "Chat",        "Is user se chat karein CallX par")));
app.get("/join/:groupId",   (req, res) => res.send(deepLinkPage(`callx://join/${req.params.groupId}`,     "", "Group Join",  "CallX group join karein")));
app.get("/g/:groupId",      (req, res) => res.send(deepLinkPage(`callx://g/${req.params.groupId}`,        "", "Group Chat",  "Is group ka chat kholein CallX mein")));
app.get("/reel/:reelId",    (req, res) => res.send(deepLinkPage(`callx://reel/${req.params.reelId}`,      "", "Reel",        "Ye reel CallX mein dekhein")));
app.get("/reels/user/:uid", (req, res) => res.send(deepLinkPage(`callx://reels/user/${req.params.uid}`,  "", "User Reels",  "Is user ke saare reels CallX mein dekhein")));
app.get("/reels/hashtag/:tag",  (req, res) => res.send(deepLinkPage(`callx://reels/hashtag/${req.params.tag}`,  "", `#${req.params.tag} Reels`, `#${req.params.tag} ke saare reels dekhein`)));
app.get("/reels/sound/:soundId",(req, res) => res.send(deepLinkPage(`callx://reels/sound/${req.params.soundId}`, "", "Sound",  "Ye sound CallX mein sune aur use karein")));
app.get("/status/:uid",     (req, res) => res.send(deepLinkPage(`callx://status/${req.params.uid}`,      "", "Status",      "Is user ka status CallX mein dekhein")));
app.get("/search",          (req, res) => {
  const q = req.query.q || "";
  res.send(deepLinkPage(`callx://search?q=${encodeURIComponent(q)}`, "", "Search", `"${q}" ko CallX mein search karein`));
});

// App section tabs
["chats", "calls", "reels", "groups", "notifications"].forEach(tab => {
  app.get(`/${tab}`, (req, res) => res.send(deepLinkPage(
    `callx://${tab}`, "",
    tab.charAt(0).toUpperCase() + tab.slice(1),
    `CallX app ka ${tab} section kholein`
  )));
});

// ══════════════════════════════════════════════════════════════════════════════
// Start server + Render keep-alive
// ══════════════════════════════════════════════════════════════════════════════
const PORT = process.env.PORT || 3000;
app.listen(PORT, () => {
  console.log("callx-server v3 on :" + PORT);

  // Render free tier ko jaagta rakho — har 14 min mein self-ping
  const https   = require("https");
  const SELF_URL = "https://callx-server.onrender.com/ping";
  setInterval(() => {
    https.get(SELF_URL, r => {
      console.log("[keep-alive] ping →", r.statusCode);
    }).on("error", e => {
      console.warn("[keep-alive] ping failed:", e.message);
    });
  }, 14 * 60 * 1000);
});
