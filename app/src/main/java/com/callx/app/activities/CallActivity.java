package com.callx.app.activities;
import android.os.Bundle;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.databinding.ActivityCallBinding;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import org.webrtc.*;
import java.util.*;
public class CallActivity extends AppCompatActivity {
    private ActivityCallBinding binding;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private String currentUid, partnerUid, callType;
    private boolean isCaller;
    private boolean micEnabled = true, cameraEnabled = true;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityCallBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        partnerUid  = getIntent().getStringExtra("partnerUid");
        callType    = getIntent().getStringExtra("callType");
        isCaller    = getIntent().getBooleanExtra("isCaller", true);
        String partnerName = getIntent().getStringExtra("partnerName");
        binding.tvCallerName.setText(partnerName != null ? partnerName : "Unknown");
        binding.tvCallStatus.setText("Connecting...");
        initWebRTC();
        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
    }
    private void initWebRTC() {
        eglBase = EglBase.create();
        PeerConnectionFactory.InitializationOptions options =
            PeerConnectionFactory.InitializationOptions.builder(this)
                .createInitializationOptions();
        PeerConnectionFactory.initialize(options);
        factory = PeerConnectionFactory.builder().createPeerConnectionFactory();
        binding.remoteVideo.init(eglBase.getEglBaseContext(), null);
        binding.localVideo.init(eglBase.getEglBaseContext(), null);
        binding.tvCallStatus.setText("Connected");
    }
    private void toggleMic() {
        micEnabled = !micEnabled;
        Toast.makeText(this, micEnabled ? "Mic On" : "Mic Off", Toast.LENGTH_SHORT).show();
    }
    private void toggleCamera() {
        cameraEnabled = !cameraEnabled;
        Toast.makeText(this, cameraEnabled ? "Camera On" : "Camera Off", Toast.LENGTH_SHORT).show();
    }
    private void endCall() {
        if (peerConnection != null) peerConnection.close();
        FirebaseDatabase.getInstance("https://sathix-97a76-default-rtdb.firebaseio.com").getReference("calls").child(partnerUid).removeValue();
        finish();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        binding.remoteVideo.release();
        binding.localVideo.release();
        if (eglBase != null) eglBase.release();
    }
}
