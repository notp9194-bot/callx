package com.callx.app.activities;
import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.callx.app.utils.Constants;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import org.json.JSONObject;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
public class ChatActivity extends AppCompatActivity {
    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private String chatId, currentUid, partnerUid, partnerName;
    private DatabaseReference messagesRef;
    private androidx.activity.result.ActivityResultLauncher<String> pickImageLauncher;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        partnerUid = getIntent().getStringExtra("partnerUid");
        partnerName = getIntent().getStringExtra("partnerName");
        binding.toolbar.setTitle(partnerName != null ? partnerName : "Chat");
        String[] ids = {currentUid, partnerUid};
        Arrays.sort(ids);
        chatId = ids[0] + "_" + ids[1];
        adapter = new MessageAdapter(messages, currentUid);
        binding.rvMessages.setLayoutManager(new LinearLayoutManager(this));
        binding.rvMessages.setAdapter(adapter);
        messagesRef = FirebaseDatabase.getInstance(com.callx.app.utils.Constants.DB_URL)
            .getReference("messages").child(chatId);
        messagesRef.addChildEventListener(new ChildEventListener() {
            public void onChildAdded(DataSnapshot s, String prev) {
                Message msg = s.getValue(Message.class);
                if (msg != null) {
                    messages.add(msg);
                    adapter.notifyItemInserted(messages.size() - 1);
                    binding.rvMessages.scrollToPosition(messages.size() - 1);
                }
            }
            public void onChildChanged(DataSnapshot s, String prev) {}
            public void onChildRemoved(DataSnapshot s) {}
            public void onChildMoved(DataSnapshot s, String prev) {}
            public void onCancelled(DatabaseError e) {}
        });
        // Image picker launcher (system gallery — no runtime perm needed)
        pickImageLauncher = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadAndSendImage(uri); });
        binding.btnSend.setOnClickListener(v -> sendMessage());
        binding.btnAttach.setOnClickListener(v -> pickImageLauncher.launch("image/*"));
    }
    private void sendMessage() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("senderId", currentUid);
        msg.put("text", text);
        msg.put("type", "text");
        msg.put("timestamp", ServerValue.TIMESTAMP);
        messagesRef.push().setValue(msg);
        binding.etMessage.setText("");
        String myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
        if (myName == null || myName.isEmpty()) myName = "CallX User";
        pushNotify(partnerUid, currentUid, myName, "message", text);
    }
    private void uploadAndSendImage(android.net.Uri uri) {
        binding.uploadProgress.setVisibility(android.view.View.VISIBLE);
        binding.btnAttach.setEnabled(false);
        String folder = "callx/" + chatId;
        com.callx.app.utils.CloudinaryUploader.upload(this, uri, folder,
            new com.callx.app.utils.CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(String secureUrl) {
                    binding.uploadProgress.setVisibility(android.view.View.GONE);
                    binding.btnAttach.setEnabled(true);
                    Map<String, Object> msg = new HashMap<>();
                    msg.put("senderId", currentUid);
                    msg.put("imageUrl", secureUrl);
                    msg.put("type", "image");
                    msg.put("timestamp", ServerValue.TIMESTAMP);
                    messagesRef.push().setValue(msg);
                    String myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
                    if (myName == null || myName.isEmpty()) myName = "CallX User";
                    pushNotify(partnerUid, currentUid, myName, "message", "📷 Photo");
                }
                @Override public void onError(String message) {
                    binding.uploadProgress.setVisibility(android.view.View.GONE);
                    binding.btnAttach.setEnabled(true);
                    android.widget.Toast.makeText(ChatActivity.this,
                        "Upload fail: " + message,
                        android.widget.Toast.LENGTH_LONG).show();
                }
            });
    }
    private void pushNotify(String toUid, String fromUid,
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
                body.put("fromName", fromName);
                body.put("type", type);
                if (text != null) body.put("text", text);
                OutputStream os = conn.getOutputStream();
                os.write(body.toString().getBytes("UTF-8"));
                os.close();
                int code = conn.getResponseCode();
                Log.d("CallX", "Msg notify: " + code);
            } catch (Exception e) {
                Log.e("CallX", "Msg notify failed: " + e.getMessage());
            } finally {
                if (conn != null) conn.disconnect();
            }
        }).start();
    }
    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
