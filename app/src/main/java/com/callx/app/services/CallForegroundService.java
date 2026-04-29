package com.callx.app.services;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import com.callx.app.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.utils.Constants;
public class CallForegroundService extends android.app.Service {
    public static final int ID = 9001;
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String name = intent != null ? intent.getStringExtra("name") : "CallX";
        Intent open = new Intent(this, CallActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new NotificationCompat.Builder(this, Constants.CHANNEL_CALLS)
            .setSmallIcon(R.drawable.ic_phone)
            .setContentTitle("CallX in progress")
            .setContentText(name == null ? "Call" : name)
            .setOngoing(true)
            .setContentIntent(pi)
            .build();
        startForeground(ID, n);
        return START_NOT_STICKY;
    }
    @Nullable @Override public IBinder onBind(Intent intent) { return null; }
}
