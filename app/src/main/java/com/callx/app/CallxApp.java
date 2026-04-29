package com.callx.app;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.util.Log;
import com.callx.app.utils.Constants;
import com.google.firebase.database.FirebaseDatabase;
public class CallxApp extends Application {
    private static final String TAG = "CallxApp";
    @Override
    public void onCreate() {
        super.onCreate();
        try {
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .setPersistenceEnabled(true);
        } catch (Exception e) {
            Log.w(TAG, "Persistence already enabled: " + e.getMessage());
        }
        createChannels();
    }
    private void createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm =
            (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;

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
        msgs.enableVibration(true);
        nm.createNotificationChannel(msgs);

        NotificationChannel groups = new NotificationChannel(
            Constants.CHANNEL_GROUPS, "Group Messages",
            NotificationManager.IMPORTANCE_HIGH);
        groups.enableVibration(true);
        nm.createNotificationChannel(groups);

        NotificationChannel status = new NotificationChannel(
            Constants.CHANNEL_STATUS, "Status / Story",
            NotificationManager.IMPORTANCE_DEFAULT);
        nm.createNotificationChannel(status);

        NotificationChannel reqs = new NotificationChannel(
            Constants.CHANNEL_REQUESTS, "Contact Requests",
            NotificationManager.IMPORTANCE_HIGH);
        reqs.enableVibration(true);
        reqs.setLockscreenVisibility(android.app.Notification.VISIBILITY_PUBLIC);
        nm.createNotificationChannel(reqs);
    }
}
