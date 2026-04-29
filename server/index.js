const express = require('express');
const admin = require('firebase-admin');

const DB_URL = "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";

// FIREBASE_SERVICE_ACCOUNT env var: paste the full service account JSON
// (or base64-encoded JSON). Get it from Firebase Console →
// Project Settings → Service Accounts → Generate new private key.
let serviceAccount;
try {
  const raw = (process.env.FIREBASE_SERVICE_ACCOUNT || '').trim();
  if (!raw) {
    console.error('FIREBASE_SERVICE_ACCOUNT env var missing');
    process.exit(1);
  }
  serviceAccount = raw.startsWith('{')
    ? JSON.parse(raw)
    : JSON.parse(Buffer.from(raw, 'base64').toString('utf8'));
} catch (e) {
  console.error('Failed to parse FIREBASE_SERVICE_ACCOUNT:', e.message);
  process.exit(1);
}

admin.initializeApp({
  credential: admin.credential.cert(serviceAccount),
  databaseURL: DB_URL,
});

const app = express();
app.use(express.json({ limit: '1mb' }));

app.get('/', (_req, res) => res.send('CallX server running ✅'));
app.get('/healthz', (_req, res) => res.json({ ok: true }));

async function getToken(uid) {
  const snap = await admin.database().ref(`users/${uid}/fcmToken`).get();
  return snap.exists() ? snap.val() : null;
}

// POST /notify
// body: { toUid, fromUid, fromName, type: 'call' | 'message', text? }
app.post('/notify', async (req, res) => {
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
      android: {
        priority: 'high',
        ttl: 60 * 1000,
      },
    };
    const id = await admin.messaging().send(message);
    return res.json({ ok: true, id });
  } catch (e) {
    console.error('notify error:', e);
    return res.status(500).json({ error: e.message });
  }
});

const PORT = process.env.PORT || 3000;
app.listen(PORT, () => console.log(`CallX server listening on :${PORT}`));
