package com.callx.app.utils;
import android.util.Log;
import okhttp3.*;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
public class PushNotify {
    private static final OkHttpClient client = new OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build();
    public static void notifyUser(String toUid, String fromUid, String fromName,
                                  String type, String text) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid", toUid)
                .put("fromUid", fromUid == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type", type)
                .put("text", text == null ? "" : text);
            Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/notify")
                .post(RequestBody.create(body.toString(),
                    MediaType.parse("application/json")))
                .build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    Log.w("PushNotify", "fail: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response response) {
                    if (response.body() != null) response.close();
                }
            });
        } catch (Exception e) {
            Log.w("PushNotify", "err: " + e.getMessage());
        }
    }
    public static void notifyMessage(String toUid, String fromUid, String fromName,
                                     String chatId, String messageId, String text,
                                     String type, String mediaUrl) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",     toUid)
                .put("fromUid",   fromUid   == null ? "" : fromUid)
                .put("fromName",  fromName  == null ? "" : fromName)
                .put("type",      type      == null ? "message" : type)
                .put("text",      text      == null ? "" : text)
                .put("chatId",    chatId    == null ? "" : chatId)
                .put("messageId", messageId == null ? "" : messageId)
                .put("mediaUrl",  mediaUrl  == null ? "" : mediaUrl);
            Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/notify")
                .post(RequestBody.create(body.toString(),
                    MediaType.parse("application/json")))
                .build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    Log.w("PushNotify", "fail: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response response) {
                    if (response.body() != null) response.close();
                }
            });
        } catch (Exception e) {
            Log.w("PushNotify", "err: " + e.getMessage());
        }
    }
    public static void notifyTyping(String toUid, String fromUid, String fromName,
                                    String chatId) {
        notifyMessage(toUid, fromUid, fromName, chatId, "", "typing…", "typing", "");
    }
    public static void notifyGroup(String groupId, String fromUid, String fromName,
                                   String type, String text) {
        try {
            JSONObject body = new JSONObject()
                .put("groupId", groupId)
                .put("fromUid", fromUid == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type", type)
                .put("text", text == null ? "" : text);
            Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/notify/group")
                .post(RequestBody.create(body.toString(),
                    MediaType.parse("application/json")))
                .build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {}
                @Override public void onResponse(Call call, Response response) {
                    if (response.body() != null) response.close();
                }
            });
        } catch (Exception e) {
            Log.w("PushNotify", "err: " + e.getMessage());
        }
    }
    public static void notifyStatus(String fromUid, String fromName) {
        try {
            JSONObject body = new JSONObject()
                .put("fromUid", fromUid == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type", "status");
            Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/notify/status")
                .post(RequestBody.create(body.toString(),
                    MediaType.parse("application/json")))
                .build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {}
                @Override public void onResponse(Call call, Response response) {
                    if (response.body() != null) response.close();
                }
            });
        } catch (Exception e) {}
    }
    // Feature 12 — receiver ne sender ko permanently block kiya. Sender
    // ko ek baar return notification jaata hai (receiver details ke saath).
    // Server side me bhi force=true honi chahiye taaki perma-block check
    // bypass ho jaaye warna message khud apne hi block check me ruk jayega.
    public static void notifyPermaBlock(String toUid, String fromUid,
                                        String fromName) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",    toUid)
                .put("fromUid",  fromUid  == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type",     "permablock_notify")
                .put("force",    true)
                .put("text",     fromName + " ne aapko permanently block kiya");
            Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/notify")
                .post(RequestBody.create(body.toString(),
                    MediaType.parse("application/json")))
                .build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    Log.w("PushNotify", "permablock fail: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response response) {
                    if (response.body() != null) response.close();
                }
            });
        } catch (Exception e) {
            Log.w("PushNotify", "permablock err: " + e.getMessage());
        }
    }
    // Feature 14/15 — sender (jo perma-block ho chuka) special request bhej raha hai.
    // force=true taaki receiver ka perma-block check bypass ho.
    public static void notifySpecialRequest(String toUid, String fromUid,
                                           String fromName, String text) {
        try {
            JSONObject body = new JSONObject()
                .put("toUid",    toUid)
                .put("fromUid",  fromUid  == null ? "" : fromUid)
                .put("fromName", fromName == null ? "" : fromName)
                .put("type",     "special_request")
                .put("force",    true)
                .put("text",     text == null ? "Please unblock me" : text);
            Request req = new Request.Builder()
                .url(Constants.SERVER_URL + "/notify")
                .post(RequestBody.create(body.toString(),
                    MediaType.parse("application/json")))
                .build();
            client.newCall(req).enqueue(new Callback() {
                @Override public void onFailure(Call call, java.io.IOException e) {
                    Log.w("PushNotify", "spreq fail: " + e.getMessage());
                }
                @Override public void onResponse(Call call, Response response) {
                    if (response.body() != null) response.close();
                }
            });
        } catch (Exception e) {
            Log.w("PushNotify", "spreq err: " + e.getMessage());
        }
    }
    private PushNotify() {}
}
