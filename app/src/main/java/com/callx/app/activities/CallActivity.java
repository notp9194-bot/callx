package com.callx.app.activities;
import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import com.callx.app.R;
import com.callx.app.databinding.ActivityCallBinding;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
public class CallActivity extends AppCompatActivity {
    private ActivityCallBinding binding;
    private String partnerUid, partnerName;
    private boolean isCaller, isVideo, micOn = true, camOn = true;
    private long startedAt = 0;
    private String callId;
    private final Handler tick = new Handler(Looper.getMainLooper());
    private Runnable ticker;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        partnerUid  = getIntent().getStringExtra("partnerUid");
        partnerName = getIntent().getStringExtra("partnerName");
        isCaller    = getIntent().getBooleanExtra("isCaller", false);
        isVideo     = getIntent().getBooleanExtra("video", false);
        if (partnerUid == null) { finish(); return; }
        binding.tvCallerName.setText(partnerName == null ? "Unknown" : partnerName);
        binding.tvCallStatus.setText(isCaller ?
            (isVideo ? "Video call ja rahi hai..." : "Calling...") : "Connecting...");
        if (!isVideo) {
            binding.localVideo.setVisibility(View.GONE);
            binding.remoteVideo.setVisibility(View.GONE);
            binding.btnToggleCamera.setVisibility(View.GONE);
        }
        String[] perms = isVideo ?
            new String[]{Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA} :
            new String[]{Manifest.permission.RECORD_AUDIO};
        boolean granted = true;
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                granted = false; break;
            }
        }
        if (!granted) {
            ActivityCompat.requestPermissions(this, perms, 401);
        }
        if (isCaller) initiateCall();
        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> {
            micOn = !micOn;
            binding.btnToggleMic.setAlpha(micOn ? 1f : 0.4f);
            Toast.makeText(this, micOn ? "Mic on" : "Mic mute", Toast.LENGTH_SHORT).show();
        });
        binding.btnToggleCamera.setOnClickListener(v -> {
            camOn = !camOn;
            binding.btnToggleCamera.setAlpha(camOn ? 1f : 0.4f);
        });
    }
    private void initiateCall() {
        String myUid  = FirebaseUtils.getCurrentUid();
        String myName = FirebaseUtils.getCurrentName();
        callId = FirebaseUtils.db().getReference("activeCalls").push().getKey();
        Map<String, Object> c = new HashMap<>();
        c.put("from", myUid); c.put("fromName", myName);
        c.put("to", partnerUid); c.put("video", isVideo);
        c.put("at", System.currentTimeMillis());
        c.put("status", "ringing");
        FirebaseUtils.db().getReference("activeCalls").child(callId).setValue(c);
        PushNotify.notifyUser(partnerUid, myUid, myName,
            isVideo ? "video_call" : "call", callId);
        // Watch status
        FirebaseUtils.db().getReference("activeCalls").child(callId)
            .child("status").addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot s) {
                    String st = s.getValue(String.class);
                    if ("accepted".equals(st)) onConnected();
                    else if ("ended".equals(st) || "rejected".equals(st)) endCall();
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private void onConnected() {
        startedAt = System.currentTimeMillis();
        binding.tvCallStatus.setText("Connected • 0:00");
        ticker = new Runnable() {
            @Override public void run() {
                long elapsed = (System.currentTimeMillis() - startedAt) / 1000;
                long m = elapsed / 60, s = elapsed % 60;
                binding.tvCallStatus.setText(
                    String.format("Connected • %d:%02d", m, s));
                tick.postDelayed(this, 1000);
            }
        };
        tick.post(ticker);
    }
    private void endCall() {
        if (ticker != null) tick.removeCallbacks(ticker);
        long duration = startedAt == 0 ? 0 : System.currentTimeMillis() - startedAt;
        String myUid = FirebaseUtils.getCurrentUid();
        // Log
        Map<String, Object> log = new HashMap<>();
        log.put("partnerUid", partnerUid);
        log.put("partnerName", partnerName);
        log.put("direction", isCaller ? "outgoing" : "incoming");
        log.put("mediaType", isVideo ? "video" : "audio");
        log.put("timestamp", System.currentTimeMillis());
        log.put("duration", duration);
        FirebaseUtils.getCallsRef(myUid).push().setValue(log);
        if (callId != null) {
            FirebaseUtils.db().getReference("activeCalls").child(callId)
                .child("status").setValue("ended");
        }
        finish();
    }
    @Override
    protected void onDestroy() {
        if (ticker != null) tick.removeCallbacks(ticker);
        super.onDestroy();
    }
}
