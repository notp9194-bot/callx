package com.callx.app.services;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.Person;
import androidx.core.app.RemoteInput;
import androidx.core.graphics.drawable.IconCompat;
import com.callx.app.CallxApp;
import com.callx.app.R;
import com.callx.app.activities.ChatActivity;
import com.callx.app.activities.IncomingCallActivity;
import com.callx.app.activities.MainActivity;
import com.callx.app.activities.SpecialRequestPopupActivity;
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
import java.util.HashMap;
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
        } else if ("permablock_notify".equals(type)) {
            // Sender ko receiver ne perma-block kar diya — return notification
            showPermaBlockReturnNotification(data);
        } else if ("special_request".equals(type)) {
            // Sender (jo perma-block ho chuka hai) ne special request bheji
            showSpecialRequestNotification(data);
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
        final String rawText    = data.getOrDefault("text", "Naya message");
        final String type       = data.getOrDefault("type", "message");
        // Feature 7+8 — type-specific preview text
        final String text = previewTextFor(type, rawText);
        long ls = 0L;
        try { ls = Long.parseLong(data.getOrDefault("fromLastSeen", "0")); }
        catch (Exception ignored) {}
        final long lastSeen = ls;
        final boolean online = (System.currentTimeMillis() - lastSeen)
                                < Constants.ONLINE_WINDOW_MS && lastSeen > 0;
        final String status = online ? "Online" : "Offline";
        final String subText = (fromMobile.isEmpty() ? "" : ("+" + fromMobile + " • "))
                               + status;
        // Stable per-sender notification id (Feature 6 — same user grouping)
        final int notifId = ("chat_" + (fromUid == null ? "" : fromUid)).hashCode();
        // Typing event → just update the existing chat notification briefly
        if ("typing".equals(type)) {
            showTypingNotification(fromUid, fromName, chatId, notifId, subText);
            return;
        }
        // Logged-out OR sender unknown → just show without mute/block check
        if (FirebaseAuth.getInstance().getCurrentUser() == null
                || fromUid == null || fromUid.isEmpty()) {
            buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, type, subText, notifId, null,
                /*muted*/ false);
            return;
        }
        final String myUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        // (Feature 4 / 12) Permanently blocked? → drop completely (no notification at all)
        FirebaseUtils.db().getReference("permaBlocked").child(myUid).child(fromUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot ps) {
                    if (Boolean.TRUE.equals(ps.getValue(Boolean.class))) {
                        return; // PERMANENT BLOCK — no notification ever
                    }
                    // (Feature 2 / 3) Block → "Unblock {name}" prompt notification
                    FirebaseUtils.db().getReference("blocked")
                        .child(myUid).child(fromUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            @Override public void onDataChange(DataSnapshot s) {
                                if (Boolean.TRUE.equals(s.getValue(Boolean.class))) {
                                    showBlockedSenderNotification(fromUid, fromName,
                                        fromMobile, fromPhoto, chatId);
                                    return;
                                }
                                // (Feature 1) Muted → still show, but silent + low priority
                                FirebaseUtils.db().getReference("muted")
                                    .child(myUid).child(fromUid)
                                    .addListenerForSingleValueEvent(
                                        new ValueEventListener() {
                                    @Override public void onDataChange(DataSnapshot s2) {
                                        boolean muted = Boolean.TRUE.equals(
                                            s2.getValue(Boolean.class));
                                        loadLast3AndBuild(myUid, fromUid, fromName,
                                            fromMobile, fromPhoto, chatId, mediaUrl,
                                            text, type, subText, notifId, muted);
                                    }
                                    @Override public void onCancelled(DatabaseError e) {
                                        loadLast3AndBuild(myUid, fromUid, fromName,
                                            fromMobile, fromPhoto, chatId, mediaUrl,
                                            text, type, subText, notifId, false);
                                    }
                                });
                            }
                            @Override public void onCancelled(DatabaseError e) {}
                        });
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private static String previewTextFor(String type, String raw) {
        if (raw != null && !raw.isEmpty()) return raw;
        if (type == null) return "Naya message";
        switch (type) {
            case "image": return "📷 Photo";
            case "video": return "🎬 Video";
            case "audio": return "🎤 Voice message";
            case "file":  return "📎 File";
            case "pdf":   return "📄 PDF document";
            default:      return "Naya message";
        }
    }
    private static int smallIconFor(String type) {
        if (type == null) return R.drawable.ic_message_notification;
        switch (type) {
            case "image": return R.drawable.ic_gallery;
            case "video": return R.drawable.ic_video;
            case "audio": return R.drawable.ic_audio;
            case "file":  return R.drawable.ic_file;
            case "pdf":   return R.drawable.ic_pdf;
            default:      return R.drawable.ic_message_notification;
        }
    }
    private void loadLast3AndBuild(final String myUid, final String fromUid,
            final String fromName, final String fromMobile, final String fromPhoto,
            final String chatId, final String mediaUrl, final String text,
            final String type, final String subText, final int notifId,
            final boolean muted) {
        if (chatId == null || chatId.isEmpty()) {
            buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, type, subText, notifId, null, muted);
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
                        t = previewTextFor(tp, "");
                    }
                    boolean fromMe = s != null && s.equals(myUid);
                    hist.add(new HistoryItem(t, ts, fromMe));
                }
                Collections.sort(hist, (a, b) -> Long.compare(a.ts, b.ts));
                buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                    chatId, mediaUrl, text, type, subText, notifId, hist, muted);
            }
            @Override public void onCancelled(DatabaseError e) {
                buildAndShow(fromUid, fromName, fromMobile, fromPhoto,
                    chatId, mediaUrl, text, type, subText, notifId, null, muted);
            }
        });
    }
    private void buildAndShow(final String fromUid, final String fromName,
            final String fromMobile, final String fromPhoto, final String chatId,
            final String mediaUrl, final String text, final String type,
            final String subText, final int notifId,
            @Nullable final List<HistoryItem> hist, final boolean muted) {
        // Avatar + my own avatar (Feature 10) + (optional) attached image are
        // downloaded off-thread, then we post the notification on the main flow.
        bg.execute(() -> {
            Bitmap avatar    = circle(downloadBitmap(fromPhoto, 256, 256));
            Bitmap myAvatar  = circle(loadMyAvatar());
            boolean isImage  = "image".equals(type)
                && mediaUrl != null && !mediaUrl.isEmpty();
            Bitmap picture = isImage ? downloadBitmap(mediaUrl, 1024, 768) : null;
            postRichNotification(fromUid, fromName, fromMobile, fromPhoto,
                chatId, mediaUrl, text, type, subText, notifId, hist,
                avatar, myAvatar, picture, muted);
        });
    }
    private void postRichNotification(String fromUid, String fromName, String fromMobile,
            String fromPhoto, String chatId, String mediaUrl, String text,
            String type, String subText, int notifId,
            @Nullable List<HistoryItem> hist,
            @Nullable Bitmap avatar, @Nullable Bitmap myAvatar,
            @Nullable Bitmap picture, boolean muted) {
        // Sender Person (with circular avatar — Feature 5)
        Person.Builder pb = new Person.Builder().setName(fromName).setKey(fromUid);
        if (avatar != null) pb.setIcon(IconCompat.createWithBitmap(avatar));
        Person sender = pb.build();
        // (Feature 10/11) Me Person — apna avatar set karo so reply right side
        // me apne profile image ke saath dikhega.
        Person.Builder meB = new Person.Builder().setName("You").setKey("me");
        if (myAvatar != null) meB.setIcon(IconCompat.createWithBitmap(myAvatar));
        Person me = meB.build();
        // (Feature 9) Open chat directly on tap
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
            buildActionIntent(Constants.ACTION_REPLY, fromUid, fromName, fromPhoto,
                chatId, notifId),
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
                fromPhoto, chatId, notifId),
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
                fromPhoto, chatId, notifId),
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
                fromPhoto, chatId, notifId),
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Action blockAction =
            new NotificationCompat.Action.Builder(
                    R.drawable.ic_message_notification, "Block", blockPi)
                .build();
        // MessagingStyle — expands to last 3 messages (Feature 7)
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
        // Channel — muted to silent channel, baki messages channel
        String channel = muted ? Constants.CHANNEL_MUTED : Constants.CHANNEL_MESSAGES;
        // Lock-screen-safe public version (no preview / no image)
        NotificationCompat.Builder publicB = new NotificationCompat.Builder(this,
                channel)
            .setSmallIcon(smallIconFor(type))
            .setContentTitle("CallX")
            .setContentText("New message")
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        // Main builder (Feature 6 — group key for same-user grouping)
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, channel)
            .setSmallIcon(smallIconFor(type))
            .setContentTitle(fromName)
            .setContentText(text)
            .setSubText(subText)
            .setShortcutId("chat_" + (chatId == null ? "" : chatId))
            .setStyle(style)
            .setPriority(muted ? NotificationCompat.PRIORITY_LOW
                               : NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setWhen(System.currentTimeMillis())
            .setShowWhen(true)
            .setAutoCancel(true)
            .setContentIntent(openPi)
            .addAction(replyAction)
            .addAction(markReadAction)
            .addAction(muteAction)
            .addAction(blockAction)
            .setGroup(Constants.GROUP_KEY_MESSAGES)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicB.build());
        if (muted) {
            b.setSilent(true);
            b.setOnlyAlertOnce(true);
        }
        if (avatar != null) b.setLargeIcon(avatar);
        // Image message → BigPictureStyle (only when unlocked)
        if (picture != null) {
            NotificationCompat.BigPictureStyle bp =
                new NotificationCompat.BigPictureStyle()
                    .bigPicture(picture)
                    .setBigContentTitle(fromName)
                    .setSummaryText(subText);
            if (avatar != null) bp.bigLargeIcon((Bitmap) null);
            b.setStyle(bp);
            b.setContentText(text);
        }
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(notifId, b.build());
        // (Feature 6) Group summary — multiple users hone par OS expand karega
        NotificationCompat.Builder summary = new NotificationCompat.Builder(this,
                channel)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle("CallX")
            .setContentText("New messages")
            .setStyle(new NotificationCompat.InboxStyle()
                .setSummaryText("CallX"))
            .setGroup(Constants.GROUP_KEY_MESSAGES)
            .setGroupSummary(true)
            .setAutoCancel(true);
        if (muted) summary.setSilent(true);
        nm.notify(Constants.GROUP_KEY_MESSAGES.hashCode(), summary.build());
    }
    private Intent buildActionIntent(String action, String fromUid, String fromName,
                                     String fromPhoto, String chatId, int notifId) {
        return new Intent(this, NotificationActionReceiver.class)
            .setAction(action)
            .putExtra(Constants.EXTRA_CHAT_ID,       chatId   == null ? "" : chatId)
            .putExtra(Constants.EXTRA_PARTNER_UID,   fromUid)
            .putExtra(Constants.EXTRA_PARTNER_NAME,  fromName)
            .putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto == null ? "" : fromPhoto)
            .putExtra(Constants.EXTRA_NOTIF_ID,      notifId);
    }
    // ----- Feature 2/3: "Unblock {sender}" prompt notification -----
    private void showBlockedSenderNotification(final String fromUid,
            final String fromName, final String fromMobile,
            final String fromPhoto, final String chatId) {
        bg.execute(() -> {
            Bitmap avatar = circle(downloadBitmap(fromPhoto, 256, 256));
            int blockNotifId = ("block_" + fromUid).hashCode();
            // Tap = unblock and reveal real notification next time
            PendingIntent unblockPi = PendingIntent.getBroadcast(this,
                blockNotifId * 10 + 5,
                buildActionIntent(Constants.ACTION_UNBLOCK, fromUid, fromName,
                    fromPhoto, chatId, blockNotifId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Long-press surfaces a visible "Permanently block" action
            PendingIntent permaPi = PendingIntent.getBroadcast(this,
                blockNotifId * 10 + 6,
                buildActionIntent(Constants.ACTION_PERMA_BLOCK, fromUid, fromName,
                    fromPhoto, chatId, blockNotifId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_BLOCK)
                .setSmallIcon(R.drawable.ic_phone_off)
                .setContentTitle("Unblock to " + fromName)
                .setContentText("Tap to unblock and see their messages")
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(fromName + " ne aapko message bheja hai. " +
                             "Aapne is sender ko block kiya hua hai. " +
                             "Tap on 'Unblock' to see their notifications again, " +
                             "or 'Block forever' to permanently block."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .setAutoCancel(true)
                .setContentIntent(unblockPi)
                .addAction(R.drawable.ic_message_notification,
                    "Unblock " + fromName, unblockPi)
                .addAction(R.drawable.ic_phone_off,
                    "Block forever", permaPi);
            if (avatar != null) b.setLargeIcon(avatar);
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(blockNotifId, b.build());
        });
    }
    // ----- Feature 12: receiver ne perma-block kiya — sender ko ek baar
    //                   return notification chala — receiver details ke saath -----
    private void showPermaBlockReturnNotification(Map<String, String> data) {
        final String fromUid   = data.getOrDefault("fromUid", "");
        final String fromName  = data.getOrDefault("fromName", "User");
        final String fromPhoto = data.getOrDefault("fromPhoto", "");
        final int notifId      = ("perma_in_" + fromUid).hashCode();
        bg.execute(() -> {
            Bitmap avatar = circle(downloadBitmap(fromPhoto, 256, 256));
            // Tap → open chat — banner already wahan dikhega
            Intent open = new Intent(this, ChatActivity.class);
            open.putExtra("partnerUid", fromUid);
            open.putExtra("partnerName", fromName);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            PendingIntent pi = PendingIntent.getActivity(this, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_BLOCK)
                .setSmallIcon(R.drawable.ic_phone_off)
                .setContentTitle(fromName + " ne aapko permanently block kiya")
                .setContentText("Tap karke special request bhejo")
                .setStyle(new NotificationCompat.BigTextStyle()
                    .bigText(fromName + " ne aapko permanently block kar diya hai. " +
                             "Aap unhe chat screen se ek special unblock request " +
                             "bhej sakte ho."))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
            if (avatar != null) b.setLargeIcon(avatar);
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(notifId, b.build());
        });
    }
    // ----- Feature 14/15/16/17: Special request notification at receiver -----
    private void showSpecialRequestNotification(final Map<String, String> data) {
        final String fromUid   = data.getOrDefault("fromUid", "");
        final String fromName  = data.getOrDefault("fromName", "User");
        final String fromPhoto = data.getOrDefault("fromPhoto", "");
        final String reqText   = data.getOrDefault("text", "Please unblock me");
        // (Feature 17) — agar app foreground hai to in-app popup bhi launch karo
        if (CallxApp.isAppInForeground()) {
            Intent popup = new Intent(this, SpecialRequestPopupActivity.class);
            popup.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_CLEAR_TOP
                | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            popup.putExtra("fromUid", fromUid);
            popup.putExtra("fromName", fromName);
            popup.putExtra("fromPhoto", fromPhoto);
            popup.putExtra("text", reqText);
            try { startActivity(popup); } catch (Exception ignored) {}
        }
        bg.execute(() -> {
            Bitmap avatar = circle(downloadBitmap(fromPhoto, 256, 256));
            final int notifId = ("spreq_" + fromUid).hashCode();
            // Unblock action button
            PendingIntent unblockPi = PendingIntent.getBroadcast(this,
                notifId * 10 + 7,
                new Intent(this, NotificationActionReceiver.class)
                    .setAction(Constants.ACTION_SPECIAL_UNBLOCK)
                    .putExtra(Constants.EXTRA_PARTNER_UID,   fromUid)
                    .putExtra(Constants.EXTRA_PARTNER_NAME,  fromName)
                    .putExtra(Constants.EXTRA_PARTNER_PHOTO, fromPhoto)
                    .putExtra(Constants.EXTRA_NOTIF_ID,      notifId),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            // Tap → open SpecialRequestPopupActivity
            Intent open = new Intent(this, SpecialRequestPopupActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            open.putExtra("fromUid", fromUid);
            open.putExtra("fromName", fromName);
            open.putExtra("fromPhoto", fromPhoto);
            open.putExtra("text", reqText);
            PendingIntent openPi = PendingIntent.getActivity(this, notifId, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                    Constants.CHANNEL_REQUESTS)
                .setSmallIcon(R.drawable.ic_person_add)
                .setContentTitle(fromName + " — Special request")
                .setContentText(reqText)
                .setStyle(new NotificationCompat.BigTextStyle()
                    .setBigContentTitle(fromName + " — Special request")
                    .bigText(reqText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_SOCIAL)
                .setAutoCancel(true)
                .setContentIntent(openPi)
                .addAction(R.drawable.ic_person_add,
                    "Please unblock " + fromName, unblockPi);
            if (avatar != null) b.setLargeIcon(avatar);
            NotificationManager nm = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
            nm.notify(notifId, b.build());
        });
    }
    // ----- Helpers: load my own avatar URL from RTDB synchronously -----
    @Nullable private Bitmap loadMyAvatar() {
        try {
            if (FirebaseAuth.getInstance().getCurrentUser() == null) return null;
            // CallxApp se cache check karo
            String url = CallxApp.getMyPhotoUrlCached();
            if (url == null || url.isEmpty()) return null;
            return downloadBitmap(url, 256, 256);
        } catch (Exception ignored) {
            return null;
        }
    }
    // ----- Feature 5/10: bitmap → circular crop (WhatsApp style) -----
    @Nullable private static Bitmap circle(@Nullable Bitmap src) {
        if (src == null) return null;
        int size = Math.min(src.getWidth(), src.getHeight());
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        RectF r = new RectF(0, 0, size, size);
        canvas.drawOval(r, paint);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        int x = (src.getWidth()  - size) / 2;
        int y = (src.getHeight() - size) / 2;
        canvas.drawBitmap(src, new Rect(x, y, x + size, y + size), r, paint);
        return out;
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
