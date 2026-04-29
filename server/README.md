# CallX Server (Render)

FCM push notification trigger server for CallX Android app.

## Deploy on Render

1. Push this repo to GitHub.
2. Go to https://dashboard.render.com → **New +** → **Web Service**.
3. Connect your GitHub repo.
4. Settings:
   - **Root Directory**: `server`
   - **Environment**: Node
   - **Build Command**: `npm install`
   - **Start Command**: `npm start`
5. Add **Environment Variable**:
   - Key: `FIREBASE_SERVICE_ACCOUNT`
   - Value: paste the full Firebase service account JSON
     (Firebase Console → Project Settings → Service Accounts → Generate new private key).
6. Deploy. Note the URL (e.g. `https://callx-server.onrender.com`).
7. Open `app/src/main/java/com/callx/app/utils/Constants.java` and update
   `SERVER_URL` to your Render URL. Rebuild APK.

## Endpoints

- `GET  /healthz` — health check
- `POST /notify`  — body: `{ toUid, fromUid, fromName, type, text? }`
