package com.callx.app.activities;
import android.content.Intent;
import android.media.Ringtone;
import android.media.RingtoneManager;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.databinding.ActivityIncomingCallBinding;
import com.callx.app.utils.FirebaseUtils;
public class IncomingCallActivity extends AppCompatActivity {
    private ActivityIncomingCallBinding binding;
    private Ringtone ringtone;
    private String callId, fromUid, fromName;
    private boolean isVideo;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityIncomingCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        callId   = getIntent().getStringExtra("callId");
        fromUid  = getIntent().getStringExtra("fromUid");
        fromName = getIntent().getStringExtra("fromName");
        isVideo  = getIntent().getBooleanExtra("video", false);
        binding.tvCallerName.setText(fromName == null ? "Unknown" : fromName);
        binding.tvCallerSub.setText(isVideo ? "Incoming video CallX..." :
            "Incoming CallX...");
        try {
            ringtone = RingtoneManager.getRingtone(this,
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE));
            if (ringtone != null) ringtone.play();
        } catch (Exception ignored) {}
        binding.btnAccept.setOnClickListener(v -> accept());
        binding.btnReject.setOnClickListener(v -> reject());
    }
    private void accept() {
        stopRing();
        if (callId != null) {
            FirebaseUtils.db().getReference("activeCalls").child(callId)
                .child("status").setValue("accepted");
        }
        Intent i = new Intent(this, CallActivity.class);
        i.putExtra("partnerUid", fromUid);
        i.putExtra("partnerName", fromName);
        i.putExtra("isCaller", false);
        i.putExtra("video", isVideo);
        startActivity(i);
        finish();
    }
    private void reject() {
        stopRing();
        if (callId != null) {
            FirebaseUtils.db().getReference("activeCalls").child(callId)
                .child("status").setValue("rejected");
        }
        finish();
    }
    private void stopRing() {
        if (ringtone != null && ringtone.isPlaying()) ringtone.stop();
    }
    @Override
    protected void onDestroy() { stopRing(); super.onDestroy(); }
}
