package com.callx.app.services;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;
import com.callx.app.R;
import com.callx.app.activities.ChatActivity;
import com.callx.app.activities.IncomingCallActivity;
import com.callx.app.activities.MainActivity;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.Query;
import com.google.firebase.database.ValueEventListener;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
public class CallxMessagingService extends FirebaseMessagingService {
    @Override public void onNewToken(String token) {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.getUserRef(uid).child("fcmToken").setValue(token);
    }
    @Override public void onMessageReceived(RemoteMessage msg) {
        Map<String, String> data = msg.getData();
        String type = data.getOrDefault("type", "message");
        if ("call".equals(type) || "video_call".equals(type)) {
            showIncomingCall(data, "video_call".equals(type));
        } else if ("group_message".equals(type)) {
            showGroupMessage(data);
        } else if ("status".equals(type)) {
            showStatus(data);
        } else if ("request".equals(type)) {
            // Request system hata diya gaya hai — kuch mat karo
        } else {
            showMessage(data);
        }
    }
    private void showRequest(Map<String, String> data) {
        String fromUid  = data.getOrDefault("fromUid", "");
        String fromName = data.getOrDefault("fromName", "Friend");
        // App khuli ho ya killed ho — dono case me bottom popup activity launch karo
        Intent popup = new Intent(this, com.callx.app.activities.RequestPopupActivity.class);
        popup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
            | Intent.FLAG_ACTIVITY_CLEAR_TOP
            | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        popup.putExtra("fromUid", fromUid);
        popup.putExtra("fromName", fromName);
        // Notification bhi dikhao taaki status bar me trace rahe
        PendingIntent pi = PendingIntent.getActivity(this, 0, popup,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_REQUESTS)
            .setSmallIcon(R.drawable.ic_person_add)
            .setContentTitle(fromName)
            .setContentText("Aapko contact request bheji hai")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(new Random().nextInt(99999), b.build());
        // Popup bhi turant launch karo
        try { startActivity(popup); } catch (Exception ignored) {}
    }
    private void showIncomingCall(Map<String, String> data, boolean isVideo) {
        Intent full = new Intent(this, IncomingCallActivity.class);
        full.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        full.putExtra("callId", data.get("text"));
        full.putExtra("fromUid", data.get("fromUid"));
        full.putExtra("fromName", data.get("fromName"));
        full.putExtra("video", isVideo);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // Show full-screen via PendingIntent
            PendingIntent pi = PendingIntent.getActivity(this, 0, full,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_phone)
                .setContentTitle(data.getOrDefault("fromName", "Incoming"))
                .setContentText(isVideo ? "Video CallX..." : "CallX...")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setFullScreenIntent(pi, true)
                .setOngoing(true)
                .setAutoCancel(true);
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(1001, b.build());
        } else {
            startActivity(full);
        }
    }
    // ----- Background-killed WhatsApp-style message notification -----
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private void showMessage(final Map<String, String> data) {
        final String fromUid    = data.getOrDefault("fromUid", "");
        final String fromName   = data.getOrDefault("fromName", "CallX");
        final String fromMobile = data.getOrDefault("fromMobile", "");
        final String fromPhoto  = data.getOrDefault("fromPhoto", "");
        final String chatId     = data.getOrDefault("chatId", "");
        final String mediaUrl   = data.getOrDefault("mediaUrl", "");
        final String text       = data.getOrDefault("text", "Naya message");
        final String type       = data.getOrDefault("type", "message");
        long ls = 0L;
        try { ls = Long.parseLong(data.getOrDefault("fromLastSeen", "0")); }
        catch (Exception ignored) {}
        final long lastSeen = ls;
        final boolean online = (System.currentTimeMillis() - lastSeen)
                                < Constants.ONLINE_WINDOW_MS && lastSeen > 0;
        final String status = online ? "Online" : "Offline";
        final String subText = (fromMobile.isEmpty() ? "" : ("+" + fromMobile + " • "))
                               + status;
        // Stable per-chat notification id so updates merge instead of stacking
        final int notifId = ("chat_" + chatId).hashCode();
        // Typing event → just update the existing chat notification briefly
        if ("typing".equals(type)) {
            showTypingNotification(fromUid, fromName, chatId, notifId, subText);
            return;
        }
        // Logged-out OR sender unknown → just show without mute/block check
        if (FirebaseAuth.getInstance().getCurrentUser() == null
                || fromUid == null || fromUid.isEmpty()) {
            buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, subText, notifId, null);
            return;
        }
        final String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        // Honour mute/block silently
        FirebaseUtils.db().getReference("blocked").child(myUid).child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    if (Boolean.TRUE.equals(s.getValue(Boolean.class))) return;
                    FirebaseUtils.db().getReference("muted")
                        .child(myUid).child(fromUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot s2) {
                                if (Boolean.TRUE.equals(s2.getValue(Boolean.class)))
                                    return;
                                loadLast3AndBuild(myUid, fromUid, fromName, fromMobile,
                                    fromPhoto, chatId, mediaUrl, text, subText, notifId);
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private void loadLast3AndBuild(final String myUid, final String fromUid,
            final String fromName, final String fromMobile, final String fromPhoto,
            final String chatId, final String mediaUrl, final String text,
            final String subText, final int notifId) {
        if (chatId == null || chatId.isEmpty()) {
            buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, subText, notifId, null);
            return;
        }
        Query q = FirebaseUtils.getMessagesRef(chatId)
            .orderByChild("timestamp").limitToLast(3);
        q.addListenerForSingleValueEvent(new ValueEventListener() {
            @Override public void onDataChange(DataSnapshot snap) {
                List<HistoryItem> hist = new ArrayList<>();
                for (DataSnapshot c : snap.getChildren()) {
                    String s   = String.valueOf(c.child("senderId").getValue());
                    String t   = c.child("text").getValue() != null
                                ? String.valueOf(c.child("text").getValue()) : "";
                    String tp  = c.child("type").getValue() != null
                                ? String.valueOf(c.child("type").getValue()) : "text";
                    Long   ts  = c.child("timestamp").getValue() != null
                                ? c.child("timestamp").getValue(Long.class)
                                : System.currentTimeMillis();
                    if (t.isEmpty()) {
                        switch (tp) {
                            case "image": t = "📷 Photo"; break;
                            case "video": t = "🎬 Video"; break;
                            case "audio": t = "🎤 Voice message"; break;
                            case "file":  t = "📎 File"; break;
                            default: t = "Media";
                        }
                    }
                    boolean fromMe = s != null && s.equals(myUid);
                    hist.add(new HistoryItem(t, ts, fromMe));
                }
                Collections.sort(hist, (a, b) -> Long.compare(a.ts, b.ts));
                buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                    chatId, mediaUrl, text, subText, notifId, hist);
            }
            @Override public void onCancelled(DatabaseError e) {
                buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                    chatId, mediaUrl, text, subText, notifId, null);
            }
        });
    }
    private void buildAndShow(final String fromUid, final String fromName,
            final String fromMobile, final String fromPhoto, final String chatId,
            final String mediaUrl, final String text, final String subText,
            final int notifId, @Nullable final List<HistoryItem> hist) {
        // Avatar + (optional) attached image are downloaded off-thread, then we
        // post the notification on the main flow.
        bg.execute(() -> {
            Bitmap avatar  = downloadBitmap(fromPhoto, 256, 256);
            // Image preview only when this message itself is a photo
            boolean isImage = text != null && text.startsWith("📷")
                && mediaUrl != null && !mediaUrl.isEmpty();
            Bitmap picture = isImage ? downloadBitmap(mediaUrl, 1024, 768) : null;
            postRichNotification(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, subText, notifId, hist, avatar, picture);
        });
    }
    private void postRichNotification(String fromUid, String fromName, String fromMobile,
            String fromPhoto, String chatId, String mediaUrl, String text,
            String subText, int notifId, @Nullable List<HistoryItem> hist,
            @Nullable Bitmap avatar, @Nullable Bitmap picture) {
        // Sender Person (with avatar)
        Person.Builder pb = new Person.Builder().setName(fromName).setKey(fromUid);
        if (avatar != null) pb.setIcon(IconCompat.createWithBitmap(avatar));
        Person sender = pb.build();
        Person me = new Person.Builder().setName("You").setKey("me").build();
        // Open chat on tap
        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("partnerUid", fromUid);
        open.putExtra("partnerName", fromName);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        // Action: Reply (inline RemoteInput)
        RemoteInput remoteInput = new RemoteInput.Builder(Constants.KEY_TEXT_REPLY)
            .setLabel("Reply…").build();
        PendingIntent replyPi = PendingIntent.getBroadcast(this, notifId * 10 + 2,
            buildActionIntent(Constants.ACTION_REPLY, fromUid, fromName, chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
        NotificationCompat.Action replyAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Reply", replyPi)
                .addRemoteInput(remoteInput)
                .setAllowGeneratedReplies(true)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                .build();
        // Action: Mark as read
        PendingIntent markReadPi = PendingIntent.getBroadcast(this, notifId * 10 + 1,
            buildActionIntent(Constants.ACTION_MARK_READ, fromUid, fromName,
                chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action markReadAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Mark as read", markReadPi)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build();
        // Action: Mute
        PendingIntent mutePi = PendingIntent.getBroadcast(this, notifId * 10 + 3,
            buildActionIntent(Constants.ACTION_MUTE, fromUid, fromName,
                chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action muteAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Mute", mutePi)
                .setSemanticAction(
                    NotificationCompat.Action.SEMANTIC_ACTION_MUTE)
                .build();
        // Action: Block
        PendingIntent blockPi = PendingIntent.getBroadcast(this, notifId * 10 + 4,
            buildActionIntent(Constants.ACTION_BLOCK, fromUid, fromName,
                chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action blockAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Block", blockPi)
                .build();
        // MessagingStyle — expands to last 3 messages
        NotificationCompat.MessagingStyle style =
            new NotificationCompat.MessagingStyle(me)
                .setConversationTitle(fromName);
        if (hist != null && !hist.isEmpty()) {
            for (HistoryItem h : hist) {
                style.addMessage(h.text, h.ts, h.fromMe ? me : sender);
            }
        } else {
            style.addMessage(text, System.currentTimeMillis(), sender);
        }
        // Lock-screen-safe public version (no preview / no image)
        NotificationCompat.Builder publicB = new NotificationCompat.Builder(this,
                Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("CallX")
            .setContentText("New message")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        // Main builder
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle(fromName)
            .setContentText(text)
            .setSubText(subText)
            .setShortcutId("chat_" + (chatId == null ? "" : chatId))
            .setStyle(style)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(replyAction)
            .addAction(markReadAction)
            .addAction(muteAction)
            .addAction(blockAction)
            // PRIVATE → on lockscreen the system shows publicVersion
            // (icon only, no image). Unlocked → full notification with photo.
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicB.build());
        if (avatar != null) b.setLargeIcon(avatar);
        // Image message → BigPictureStyle (only when unlocked, public hides it)
        if (picture != null) {
            NotificationCompat.BigPictureStyle bp =
                new NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .setBigContentTitle(fromName)
                    .setSummaryText(subText);
            if (avatar != null) bp.bigLargeIcon((Bitmap) null);
            b.setStyle(bp);
            b.setContentText("📷 Photo");
        }
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());
    }
    private Intent buildActionIntent(String action, String fromUid, String fromName,
                                     String chatId, int notifId) {
        return new Intent(this, NotificationActionReceiver.class)
            .setAction(action)
            .putExtra(Constants.EXTRA_CHAT_ID,      chatId == null ? "" : chatId)
            .putExtra(Constants.EXTRA_PARTNER_UID,  fromUid)
            .putExtra(Constants.EXTRA_PARTNER_NAME, fromName)
            .putExtra(Constants.EXTRA_NOTIF_ID,     notifId);
    }
    private void showTypingNotification(String fromUid, String fromName,
                                        String chatId, int notifId, String subText) {
        Intent open = new Intent(this, ChatActivity.class);
        open.putExtra("partnerUid", fromUid);
        open.putExtra("partnerName", fromName);
        open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle(fromName)
            .setContentText("typing…")
            .setSubText(subText)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openPi);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b.setTimeoutAfter(7000);
        }
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());
    }
    @Nullable private Bitmap downloadBitmap(String url, int maxW, int maxH) {
        if (url == null || url.isEmpty()) return null;
        HttpURLConnection conn = null;
        InputStream is = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(8000);
            conn.connect();
            if (conn.getResponseCode() != 200) return null;
            is = conn.getInputStream();
            Bitmap bmp = BitmapFactory.decodeStream(is);
            if (bmp == null) return null;
            int w = bmp.getWidth(), h = bmp.getHeight();
            if (w <= maxW && h <= maxH) return bmp;
            float r = Math.min((float) maxW / w, (float) maxH / h);
            return Bitmap.createScaledBitmap(bmp,
                Math.max(1, (int)(w * r)), Math.max(1, (int)(h * r)), true);
        } catch (Exception e) {
            return null;
        } finally {
            try { if (is != null) is.close(); } catch (Exception ignored) {}
            if (conn != null) conn.disconnect();
        }
    }
    private static class HistoryItem {
        final String text; final long ts; final boolean fromMe;
        HistoryItem(String t, long s, boolean me) { text = t; ts = s; fromMe = me; }
    }
    private void showGroupMessage(Map<String, String> data) {
        String title = data.getOrDefault("fromName", "Group");
        String body  = data.getOrDefault("text", "Naya group message");
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_GROUPS)
            .setSmallIcon(R.drawable.ic_group)
            .setContentTitle(title + " (group)")
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(new Random().nextInt(99999), b.build());
    }
    private void showStatus(Map<String, String> data) {
        String name = data.getOrDefault("fromName", "Friend");
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_STATUS)
            .setSmallIcon(R.drawable.ic_status_notification)
            .setContentTitle(name)
            .setContentText("Naya status post kiya")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(new Random().nextInt(99999), b.build());
    }
}
