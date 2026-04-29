package com.callx.app;
import android.app.Activity;
import android.app.Application;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.RingtoneManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import com.callx.app.activities.RequestPopupActivity;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.ChildEventListener;
import com.google.firebase.database.DataSnapshot;
import com.google.firebase.database.DatabaseError;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.lang.ref.WeakReference;
import java.util.HashSet;
public class CallxApp extends Application {
    private static final String TAG = "CallxApp";
    private WeakReference<Activity> currentActivity = new WeakReference<>(null);
    private final HashSet<String> shownRequestKeys = new HashSet<>();
    private final long requestsBaselineTs =
        System.currentTimeMillis() - (5L * 60 * 1000);
    private DatabaseReference requestsRef;
    private ChildEventListener requestsListener;
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
        trackForegroundActivity();
        // Auth ready hote hi global request listener attach karo
        FirebaseAuth.getInstance().addAuthStateListener(auth -> {
            if (auth.getCurrentUser() != null) attachRequestsListener();
            else detachRequestsListener();
        });
    }
    private void trackForegroundActivity() {
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            @Override public void onActivityCreated(Activity a, Bundle b) {}
            @Override public void onActivityStarted(Activity a) {}
            @Override public void onActivityResumed(Activity a) {
                currentActivity = new WeakReference<>(a);
            }
            @Override public void onActivityPaused(Activity a) {}
            @Override public void onActivityStopped(Activity a) {}
            @Override public void onActivitySaveInstanceState(Activity a, Bundle b) {}
            @Override public void onActivityDestroyed(Activity a) {}
        });
    }
    private void attachRequestsListener() {
        if (requestsListener != null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        requestsRef = FirebaseDatabase.getInstance(Constants.DB_URL)
            .getReference("requests").child(uid);
        requestsListener = new ChildEventListener() {
            @Override public void onChildAdded(DataSnapshot s, String prev) {
                String key = s.getKey();
                if (key == null || !shownRequestKeys.add(key)) return;
                Long at = s.child("at").getValue(Long.class);
                if (at != null && at < requestsBaselineTs) return;
                String fromUid  = s.child("fromUid").getValue(String.class);
                String fromName = s.child("fromName").getValue(String.class);
                if (fromName == null) fromName = s.child("name").getValue(String.class);
                if (fromUid == null)  fromUid  = key;
                if (fromName == null) fromName = "Friend";
                launchPopup(fromUid, fromName);
            }
            @Override public void onChildChanged(DataSnapshot s, String p) {}
            @Override public void onChildRemoved(DataSnapshot s) {
                if (s.getKey() != null) shownRequestKeys.remove(s.getKey());
            }
            @Override public void onChildMoved(DataSnapshot s, String p) {}
            @Override public void onCancelled(DatabaseError e) {
                Log.w(TAG, "requests listener cancelled: " + e.getMessage());
            }
        };
        requestsRef.addChildEventListener(requestsListener);
    }
    private void detachRequestsListener() {
        if (requestsRef != null && requestsListener != null) {
            requestsRef.removeEventListener(requestsListener);
        }
        requestsListener = null;
        requestsRef = null;
        shownRequestKeys.clear();
    }
    private void launchPopup(String fromUid, String fromName) {
        Activity a = currentActivity.get();
        Intent i = new Intent(
            a != null ? a : getApplicationContext(), RequestPopupActivity.class);
        i.putExtra("fromUid", fromUid);
        i.putExtra("fromName", fromName);
        if (a != null) {
            a.startActivity(i);
        } else {
            // App background me — naya task chahiye
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try { startActivity(i); } catch (Exception ignored) {}
        }
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
