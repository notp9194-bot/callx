package com.callx.app.activities;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.databinding.ActivityCallBinding;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import org.json.JSONObject;
import org.webrtc.*;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
public class CallActivity extends AppCompatActivity {
    private ActivityCallBinding binding;
    private PeerConnectionFactory factory;
    private PeerConnection peerConnection;
    private EglBase eglBase;
    private String currentUid, partnerUid, callType;
    private String partnerName;
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
        partnerName = getIntent().getStringExtra("partnerName");
        binding.tvCallerName.setText(partnerName != null ? partnerName : "Unknown");
        binding.tvCallStatus.setText("Connecting...");
        initWebRTC();
        binding.btnEndCall.setOnClickListener(v -> endCall());
        binding.btnToggleMic.setOnClickListener(v -> toggleMic());
        binding.btnToggleCamera.setOnClickListener(v -> toggleCamera());
        // Caller side — receiver ko FCM push bhejo (server via)
        if (isCaller && partnerUid != null) {
            String myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
            if (myName == null || myName.isEmpty()) myName = "CallX User";
            // Firebase me bhi call entry (history / ongoing call ke liye)
            Map<String, Object> callData = new HashMap<>();
            callData.put("fromUid", currentUid);
            callData.put("fromName", myName);
            callData.put("timestamp", System.currentTimeMillis());
            FirebaseDatabase.getInstance(Constants.DB_URL)
                .getReference("calls").child(partnerUid).setValue(callData);
            // Server pe POST /notify
            sendNotifyRequest(partnerUid, currentUid, myName, "call", null);
        }
    }
    private void sendNotifyRequest(String toUid, String fromUid,
                                   String fromName, String type, String text) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            try {
                URL url = new URL(Constants.SERVER_URL + "/notify");
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(8000);
                conn.setDoOutput(true);
                JSONObject body = new JSONObject();
                body.put("toUid", toUid);
                body.put("fromUid", fromUid);
                body.put("fromName", fromName != null ? fromName : "");
                body.put("type", type);
                if (text != null) body.put("text", text);
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();
                int code = conn.getResponseCode();
                Log.d("CallX", "Notify response: " + code);
            } catch (Exception e) {
                Log.e("CallX", "Notify failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
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
        FirebaseDatabase.getInstance("https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("calls").child(partnerUid).removeValue();
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
