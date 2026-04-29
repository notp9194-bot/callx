package com.callx.app.utils;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;
public class CloudinaryUploader {
    private static final String TAG = "Cloudinary";
    public interface UploadCallback {
        void onSuccess(String secureUrl);
        void onError(String message);
    }
    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build();
    public static void upload(Context ctx, Uri uri, String folder, UploadCallback cb) {
        new Thread(() -> {
            try {
                // 1. Read image bytes from URI
                byte[] bytes = readBytes(ctx, uri);
                if (bytes == null || bytes.length == 0) {
                    post(cb, false, null, "Empty file");
                    return;
                }
                // 2. Get signature from server
                JSONObject signReqBody = new JSONObject();
                if (folder != null) signReqBody.put("folder", folder);
                Request signReq = new Request.Builder()
                    .url(Constants.SERVER_URL + "/cloudinary/sign")
                    .post(RequestBody.create(
                        signReqBody.toString(),
                        MediaType.parse("application/json")))
                    .build();
                Response signRes = client.newCall(signReq).execute();
                if (!signRes.isSuccessful()) {
                    post(cb, false, null, "Sign failed: " + signRes.code());
                    return;
                }
                JSONObject signJson = new JSONObject(signRes.body().string());
                signRes.close();
                String signature = signJson.getString("signature");
                String timestamp = String.valueOf(signJson.getLong("timestamp"));
                String apiKey    = signJson.getString("api_key");
                String cloudName = signJson.optString("cloud_name",
                    Constants.CLOUDINARY_CLOUD_NAME);
                String f         = signJson.optString("folder", "callx");
                // 3. Upload directly to Cloudinary
                MultipartBody.Builder mp = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", "upload.jpg",
                        RequestBody.create(bytes,
                            MediaType.parse("application/octet-stream")))
                    .addFormDataPart("api_key", apiKey)
                    .addFormDataPart("timestamp", timestamp)
                    .addFormDataPart("signature", signature)
                    .addFormDataPart("folder", f);
                Request upReq = new Request.Builder()
                    .url("https://api.cloudinary.com/v1_1/" + cloudName + "/auto/upload")
                    .post(mp.build())
                    .build();
                Response upRes = client.newCall(upReq).execute();
                String body = upRes.body() != null ? upRes.body().string() : "";
                upRes.close();
                if (!upRes.isSuccessful()) {
                    Log.e(TAG, "Upload failed: " + body);
                    post(cb, false, null, "Upload failed: " + upRes.code());
                    return;
                }
                JSONObject upJson = new JSONObject(body);
                String secureUrl = upJson.optString("secure_url",
                    upJson.optString("url"));
                if (secureUrl == null || secureUrl.isEmpty()) {
                    post(cb, false, null, "No URL in response");
                    return;
                }
                post(cb, true, secureUrl, null);
            } catch (Exception e) {
                Log.e(TAG, "Upload error", e);
                post(cb, false, null, e.getMessage());
            }
        }).start();
    }
    private static byte[] readBytes(Context ctx, Uri uri) throws IOException {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
    private static void post(UploadCallback cb, boolean ok,
                             String url, String err) {
        new Handler(Looper.getMainLooper()).post(() -> {
            if (ok) cb.onSuccess(url);
            else cb.onError(err);
        });
    }
    private CloudinaryUploader() {}
}
