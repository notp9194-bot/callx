package com.callx.app.activities;
import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.callx.app.R;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.callx.app.utils.AudioRecorderHelper;
import com.callx.app.utils.CloudinaryUploader;
import com.callx.app.utils.FileUtils;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
public class GroupChatActivity extends AppCompatActivity {
    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private final List<Message> messages = new ArrayList<>();
    private String groupId, groupName, currentUid, currentName;
    private final AudioRecorderHelper recorder = new AudioRecorderHelper();
    private boolean isRecording = false;
    private ActivityResultLauncher<String> imagePicker, videoPicker, audioPicker, filePicker;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        groupId   = getIntent().getStringExtra("groupId");
        groupName = getIntent().getStringExtra("groupName");
        if (groupId == null || FirebaseAuth.getInstance().getCurrentUser() == null) {
            finish(); return;
        }
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        currentName = FirebaseUtils.getCurrentName();
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle(groupName != null ? groupName : "Group");
        }
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.rvMessages.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MessageAdapter(messages, currentUid, true);
        binding.rvMessages.setAdapter(adapter);
        binding.etMessage.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                boolean has = s.toString().trim().length() > 0;
                binding.btnSend.setVisibility(has ? View.VISIBLE : View.GONE);
                binding.btnMic.setVisibility(has ? View.GONE : View.VISIBLE);
            }
        });
        imagePicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadAndSend(uri, "image", "image", null); });
        videoPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadAndSend(uri, "video", "video", null); });
        audioPicker = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> { if (uri != null) uploadAndSend(uri, "audio", "video", null); });
        filePicker  = registerForActivityResult(
            new ActivityResultContracts.GetContent(),
            uri -> {
                if (uri == null) return;
                String name = FileUtils.fileName(this, uri);
                uploadAndSend(uri, "file", "raw", name);
            });
        binding.btnAttach.setOnClickListener(v -> {
            BottomSheetDialog sheet = new BottomSheetDialog(this);
            View vw = LayoutInflater.from(this).inflate(R.layout.bottom_sheet_attach, null);
            vw.findViewById(R.id.opt_gallery).setOnClickListener(x -> {
                sheet.dismiss(); imagePicker.launch("image/*"); });
            vw.findViewById(R.id.opt_video).setOnClickListener(x -> {
                sheet.dismiss(); videoPicker.launch("video/*"); });
            vw.findViewById(R.id.opt_audio).setOnClickListener(x -> {
                sheet.dismiss(); audioPicker.launch("audio/*"); });
            vw.findViewById(R.id.opt_file).setOnClickListener(x -> {
                sheet.dismiss(); filePicker.launch("application/pdf"); });
            sheet.setContentView(vw); sheet.show();
        });
        binding.btnCamera.setOnClickListener(v -> imagePicker.launch("image/*"));
        binding.btnSend.setOnClickListener(v -> sendText());
        binding.btnMic.setOnClickListener(v -> {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, 200);
                return;
            }
            if (!isRecording) {
                if (recorder.start(this)) {
                    isRecording = true;
                    binding.btnMic.setBackgroundResource(R.drawable.circle_reject);
                }
            } else {
                isRecording = false;
                binding.btnMic.setBackgroundResource(R.drawable.circle_primary);
                Uri u = recorder.stop(this);
                if (u != null) uploadAndSend(u, "audio", "video", null);
            }
        });
        loadMessages();
    }
    private void sendText() {
        String txt = binding.etMessage.getText().toString().trim();
        if (txt.isEmpty()) return;
        Message m = new Message();
        m.senderId = currentUid;
        m.senderName = currentName;
        m.text = txt;
        m.type = "text";
        m.timestamp = System.currentTimeMillis();
        push(m, txt);
        binding.etMessage.setText("");
    }
    private void uploadAndSend(Uri uri, String msgType, String rt, String fileName) {
        binding.uploadProgress.setVisibility(View.VISIBLE);
        long size = FileUtils.fileSize(this, uri);
        CloudinaryUploader.upload(this, uri, "callx/groups/" + msgType, rt,
            new CloudinaryUploader.UploadCallback() {
                @Override public void onSuccess(CloudinaryUploader.Result r) {
                    binding.uploadProgress.setVisibility(View.GONE);
                    Message m = new Message();
                    m.senderId = currentUid;
                    m.senderName = currentName;
                    m.type = msgType;
                    m.mediaUrl = r.secureUrl;
                    m.imageUrl = "image".equals(msgType) ? r.secureUrl : null;
                    m.fileName = fileName;
                    m.fileSize = r.bytes != null ? r.bytes : size;
                    m.duration = r.durationMs;
                    m.timestamp = System.currentTimeMillis();
                    String preview;
                    switch (msgType) {
                        case "image": preview = "📷 Photo"; break;
                        case "video": preview = "🎬 Video"; break;
                        case "audio": preview = "🎤 Voice"; break;
                        case "file":  preview = "📎 " +
                            (fileName == null ? "File" : fileName); break;
                        default: preview = "Media";
                    }
                    push(m, preview);
                }
                @Override public void onError(String err) {
                    binding.uploadProgress.setVisibility(View.GONE);
                    Toast.makeText(GroupChatActivity.this,
                        err == null ? "Upload fail" : err, Toast.LENGTH_LONG).show();
                }
            });
    }
    private void push(Message m, String preview) {
        DatabaseReference ref = FirebaseUtils.getGroupMessagesRef(groupId).push();
        m.id = ref.getKey();
        ref.setValue(m);
        Map<String, Object> meta = new HashMap<>();
        meta.put("lastMessage", currentName + ": " + preview);
        meta.put("lastMessageAt", System.currentTimeMillis());
        FirebaseUtils.getGroupsRef().child(groupId).updateChildren(meta);
        PushNotify.notifyGroup(groupId, currentUid, currentName,
            "group_message", preview);
    }
    private void loadMessages() {
        FirebaseUtils.getGroupMessagesRef(groupId).orderByChild("timestamp")
            .addChildEventListener(new ChildEventListener() {
                @Override public void onChildAdded(DataSnapshot snap, String prev) {
                    Message m = snap.getValue(Message.class);
                    if (m != null) {
                        if (m.id == null) m.id = snap.getKey();
                        messages.add(m);
                        adapter.notifyItemInserted(messages.size() - 1);
                        binding.rvMessages.scrollToPosition(messages.size() - 1);
                    }
                }
                @Override public void onChildChanged(DataSnapshot s, String p) {}
                @Override public void onChildRemoved(DataSnapshot s) {}
                @Override public void onChildMoved(DataSnapshot s, String p) {}
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
}
