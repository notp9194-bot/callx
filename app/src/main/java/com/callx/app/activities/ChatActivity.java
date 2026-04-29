package com.callx.app.activities;
import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import com.callx.app.adapters.MessageAdapter;
import com.callx.app.databinding.ActivityChatBinding;
import com.callx.app.models.Message;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
public class ChatActivity extends AppCompatActivity {
    private ActivityChatBinding binding;
    private MessageAdapter adapter;
    private List<Message> messages = new ArrayList<>();
    private String chatId, currentUid, partnerUid;
    private DatabaseReference messagesRef;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivityChatBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        partnerUid = getIntent().getStringExtra("partnerUid");
        String partnerName = getIntent().getStringExtra("partnerName");
        binding.toolbar.setTitle(partnerName != null ? partnerName : "Chat");
        String[] ids = {currentUid, partnerUid};
        Arrays.sort(ids);
        chatId = ids[0] + "_" + ids[1];
        adapter = new MessageAdapter(messages, currentUid);
        binding.rvMessages.setLayoutManager(new LinearLayoutManager(this));
        binding.rvMessages.setAdapter(adapter);
        messagesRef = FirebaseDatabase.getInstance("https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app").getReference("messages").child(chatId);
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
        binding.btnSend.setOnClickListener(v -> sendMessage());
    }
    private void sendMessage() {
        String text = binding.etMessage.getText().toString().trim();
        if (text.isEmpty()) return;
        Map<String, Object> msg = new HashMap<>();
        msg.put("senderId", currentUid);
        msg.put("text", text);
        msg.put("timestamp", ServerValue.TIMESTAMP);
        messagesRef.push().setValue(msg);
        binding.etMessage.setText("");
    }
    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
