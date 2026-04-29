package com.callx.app.services;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import androidx.core.app.NotificationCompat;
import com.callx.app.R;
import com.callx.app.activities.ChatActivity;
import com.callx.app.activities.IncomingCallActivity;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.FirebaseDatabase;
import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;
import java.util.Map;
public class CallxMessagingService extends FirebaseMessagingService {
    @Override
    public void onNewToken(String token) {
        super.onNewToken(token);
        if (FirebaseAuth.getInstance().getCurrentUser() != null) {
            String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("users").child(uid).child("fcmToken").setValue(token);
        }
    }
    @Override
    public void onMessageReceived(RemoteMessage msg) {
        super.onMessageReceived(msg);
        Map<String, String> data = msg.getData();
        if (data == null || data.isEmpty()) return;
        createChannels();
        String type = data.get("type");
        if ("call".equals(type)) {
            showIncomingCall(data.get("fromUid"), data.get("fromName"));
        } else {
            showMessageNotification(
                data.get("fromUid"),
                data.get("fromName"),
                data.get("text"));
        }
    }
    private void showIncomingCall(String fromUid, String fromName) {
        Intent fullScreen = new Intent(this, IncomingCallActivity.class);
        fullScreen.putExtra("fromUid", fromUid);
        fullScreen.putExtra("fromName", fromName);
        fullScreen.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK |
            Intent.FLAG_ACTIVITY_CLEAR_TOP |
            Intent.FLAG_ACTIVITY_NO_HISTORY);
        PendingIntent pi = PendingIntent.getActivity(
            this, 1001, fullScreen,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n =
            new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
                .setSmallIcon(R.drawable.ic_call_notification)
                .setContentTitle("Incoming CallX")
                .setContentText((fromName != null ? fromName : "Unknown") + " calling...")
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                .setFullScreenIntent(pi, true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setOngoing(true)
                .build();
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify(1001, n);
        // Also try direct launch (works when screen on / app foreground)
        try { startActivity(fullScreen); } catch (Exception ignored) {}
    }
    private void showMessageNotification(String fromUid, String fromName, String text) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("partnerUid", fromUid);
        intent.putExtra("partnerName", fromName);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent pi = PendingIntent.getActivity(
            this, (fromUid != null ? fromUid.hashCode() : 2001), intent,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        android.app.Notification n =
            new NotificationCompat.Builder(this, Constants.CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_message_notification)
                .setContentTitle(fromName != null ? fromName : "New message")
                .setContentText(text != null ? text : "")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build();
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.notify((int) System.currentTimeMillis(), n);
    }
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        NotificationChannel calls = new NotificationChannel(
            Constants.CHANNEL_CALLS, "Incoming Calls",
            NotificationManager.IMPORTANCE_HIGH);
        AudioAttributes attrs = new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build();
        calls.setSound(
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE), attrs);
        calls.enableVibration(true);
        calls.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(calls);
        NotificationChannel msgs = new NotificationChannel(
            Constants.CHANNEL_MESSAGES, "Messages",
            NotificationManager.IMPORTANCE_HIGH);
        nm.createNotificationChannel(msgs);
    }
}
