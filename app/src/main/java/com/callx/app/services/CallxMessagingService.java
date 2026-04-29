package com.callx.app.services;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.callx.app.R;
import com.callx.app.activities.IncomingCallActivity;
import com.callx.app.activities.MainActivity;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;
import java.util.Random;
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
    private void showMessage(Map<String, String> data) {
        String title = data.getOrDefault("fromName", "CallX");
        String body  = data.getOrDefault("text", "Naya message");
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, i,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this,
                Constants.CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_message_notification)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(pi);
        NotificationManager nm = (NotificationManager)
            getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(new Random().nextInt(99999), b.build());
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
