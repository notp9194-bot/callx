'use strict';
const express     = require('express');
const helmet      = require('helmet');
const compression = require('compression');
const cors        = require('cors');
const morgan      = require('morgan');
const rateLimit   = require('express-rate-limit');
const admin       = require('firebase-admin');
const cloudinary  = require('cloudinary').v2;

const DB_URL = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";

// ---------- Firebase Admin ----------
let serviceAccount;
try {
  const raw = (process.env.FIREBASE_SERVICE_ACCOUNT || '').trim();
  if (!raw) {
    console.error('FATAL: FIREBASE_SERVICE_ACCOUNT env var missing');
    process.exit(1);
  }
  serviceAccount = raw.startsWith('{')
    ? JSON.parse(raw)
    : JSON.parse(Buffer.from(raw, 'base64').toString('utf8'));
} catch (e) {
  console.error('FATAL: Failed to parse FIREBASE_SERVICE_ACCOUNT:', e.message);
  process.exit(1);
}
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: DB_URL,
});

// ---------- Cloudinary ----------
const CLD_NAME   = process.env.CLOUDINARY_CLOUD_NAME;
const CLD_KEY    = process.env.CLOUDINARY_API_KEY;
const CLD_SECRET = process.env.CLOUDINARY_API_SECRET;
if (!CLD_NAME || !CLD_KEY || !CLD_SECRET) {
  console.warn('WARN: Cloudinary env vars missing — /cloudinary/sign disabled');
} else {
  cloudinary.config({
    cloud_name: CLD_NAME,
    api_key: CLD_KEY,
    api_secret: CLD_SECRET,
    secure: true,
  });
}

// ---------- Express app ----------
const app = express();
app.set('trust proxy', 1); // Render uses a proxy
app.disable('x-powered-by');
app.use(helmet());
app.use(compression());
app.use(cors());
app.use(express.json({ limit: '1mb' }));
app.use(morgan('tiny'));

// Rate limits — abuse protection
const notifyLimiter = rateLimit({
  windowMs: 60 * 1000, max: 60,
  standardHeaders: true, legacyHeaders: false,
});
const signLimiter = rateLimit({
  windowMs: 60 * 1000, max: 30,
  standardHeaders: true, legacyHeaders: false,
});

app.get('/', (_req, res) => res.type('text/plain').send('CallX server running ✅'));
app.get('/healthz', (_req, res) => res.json({
  ok: true,
  uptime: process.uptime(),
  cloudinary: !!(CLD_NAME && CLD_KEY && CLD_SECRET),
}));

// ---------- FCM ----------
async function getToken(uid) {
  const snap = await admin.database().ref(`users/${uid}/fcmToken`).get();
  return snap.exists() ? snap.val() : null;
}
app.post('/notify', notifyLimiter, async (req, res) => {
  try {
    const { toUid, fromUid, fromName, type, text } = req.body || {};
    if (!toUid || !type) {
      return res.status(400).json({ error: 'toUid & type required' });
    }
    const token = await getToken(toUid);
    if (!token) {
      return res.status(404).json({ error: 'Receiver token not found' });
    }
    const message = {
      token,
      data: {
        type: String(type),
        fromUid: String(fromUid || ''),
        fromName: String(fromName || ''),
        text: String(text || ''),
      },
      android: { priority: 'high', ttl: 60 * 1000 },
    };
    const id = await admin.messaging().send(message);
    return res.json({ ok: true, id });
  } catch (e) {
    console.error('notify error:', e.message);
    // Stale token → clean up so we don't retry it
    if (e.code === 'messaging/registration-token-not-registered'
        && req.body && req.body.toUid) {
      try {
        await admin.database()
          .ref(`users/${req.body.toUid}/fcmToken`).remove();
      } catch (_) {}
    }
    return res.status(500).json({ error: e.message });
  }
});

// ---------- Cloudinary signed-upload signing ----------
// POST /cloudinary/sign  body: { folder?: string }
// Returns: { signature, timestamp, api_key, cloud_name, folder }
app.post('/cloudinary/sign', signLimiter, (req, res) => {
  try {
    if (!CLD_NAME || !CLD_KEY || !CLD_SECRET) {
      return res.status(503).json({ error: 'Cloudinary not configured' });
    }
    const timestamp = Math.round(Date.now() / 1000);
    const folder = (req.body && typeof req.body.folder === 'string'
      && req.body.folder.trim()) ? req.body.folder.trim() : 'callx';
    const paramsToSign = { timestamp, folder };
    const signature = cloudinary.utils.api_sign_request(
      paramsToSign, CLD_SECRET);
    return res.json({
      signature, timestamp, folder,
      api_key: CLD_KEY,
      cloud_name: CLD_NAME,
    });
  } catch (e) {
    console.error('sign error:', e.message);
    return res.status(500).json({ error: e.message });
  }
});

// ---------- 404 + error middleware ----------
app.use((req, res) => res.status(404).json({ error: 'Not found' }));
app.use((err, _req, res, _next) => {
  console.error('Unhandled:', err);
  res.status(500).json({ error: 'Internal server error' });
});

// ---------- Start ----------
const PORT = process.env.PORT || 3000;
const server = app.listen(PORT,
  () => console.log(`CallX server listening on :${PORT}`));

// Graceful shutdown
function shutdown(sig) {
  console.log(`${sig} received — shutting down`);
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(1), 10000).unref();
}
process.on('SIGTERM', () => shutdown('SIGTERM'));
process.on('SIGINT',  () => shutdown('SIGINT'));
process.on('unhandledRejection', (r) => console.error('unhandledRejection:', r));
process.on('uncaughtException',  (e) => console.error('uncaughtException:', e));
