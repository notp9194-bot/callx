# CallX Server (Render)

Production Node.js server for CallX:
- **FCM push** notifications (calls + messages)
- **Cloudinary signed-upload** signing (storage)

## Deploy on Render

1. Push this repo to GitHub.
2. Go to https://dashboard.render.com → **New +** → **Web Service**.
3. Connect your repo.
4. Settings:
   - **Root Directory**: `server`
   - **Environment**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
   - **Health Check Path**: `/healthz`
5. Add **Environment Variables** (Settings → Environment):

   | Key | Value |
   | --- | --- |
   | `FIREBASE_SERVICE_ACCOUNT` | full Firebase service account JSON |
   | `CLOUDINARY_CLOUD_NAME` | `dvqqgqdls` |
   | `CLOUDINARY_API_KEY` | your Cloudinary API key |
   | `CLOUDINARY_API_SECRET` | your Cloudinary API secret |
   | `NODE_ENV` | `production` |

6. Deploy. URL: `https://callx-server.onrender.com`.

## Endpoints

- `GET  /`               — liveness ping
- `GET  /healthz`        — health JSON `{ ok, uptime, cloudinary }`
- `POST /notify`         — FCM push. body: `{ toUid, fromUid, fromName, type, text? }`
- `POST /cloudinary/sign` — signed upload params. body: `{ folder? }` →
  `{ signature, timestamp, api_key, cloud_name, folder }`

## Production hardening

- `helmet` security headers, `compression`, `cors`
- `morgan` request logging, `trust proxy` for Render
- Rate limits: 60/min for `/notify`, 30/min for `/cloudinary/sign`
- Stale FCM tokens auto-pruned
- Graceful shutdown on SIGTERM/SIGINT
