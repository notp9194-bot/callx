package com.callx.app.activities;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import com.bumptech.glide.Glide;
import com.callx.app.databinding.ActivitySearchBinding;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
public class SearchActivity extends AppCompatActivity {
    private ActivitySearchBinding binding;
    private String foundUid, foundName;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        binding = ActivitySearchBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        binding.toolbar.setNavigationOnClickListener(v -> finish());
        binding.btnSearch.setOnClickListener(v -> search());
        binding.btnSendRequest.setOnClickListener(v -> sendRequest());
    }
    private void search() {
        String id = binding.etSearchId.getText().toString().trim().toLowerCase();
        if (id.isEmpty()) {
            Toast.makeText(this, "ID daalo", Toast.LENGTH_SHORT).show(); return;
        }
        binding.tvStatus.setVisibility(View.VISIBLE);
        binding.tvStatus.setText("Dhundh raha hoon...");
        binding.llResult.setVisibility(View.GONE);
        FirebaseUtils.db().getReference("users").orderByChild("callxId").equalTo(id)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    if (!snap.exists()) {
                        binding.tvStatus.setText("Koi user nahi mila");
                        return;
                    }
                    for (DataSnapshot c : snap.getChildren()) {
                        foundUid  = c.child("uid").getValue(String.class);
                        foundName = c.child("name").getValue(String.class);
                        String foundId = c.child("callxId").getValue(String.class);
                        String photo = c.child("photoUrl").getValue(String.class);
                        binding.tvResultName.setText(foundName == null ? "User" : foundName);
                        binding.tvResultId.setText(foundId == null ? "" : foundId);
                        if (photo != null && !photo.isEmpty()) {
                            Glide.with(SearchActivity.this).load(photo)
                                .into(binding.ivResultAvatar);
                        }
                        binding.llResult.setVisibility(View.VISIBLE);
                        binding.tvStatus.setVisibility(View.GONE);
                        break;
                    }
                }
                @Override public void onCancelled(DatabaseError e) {
                    binding.tvStatus.setText("Error: " + e.getMessage());
                }
            });
    }
    private void sendRequest() {
        if (foundUid == null) return;
        String myUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String myName = FirebaseUtils.getCurrentName();
        Map<String, Object> req = new HashMap<>();
        req.put("uid", myUid);
        req.put("fromUid", myUid);
        req.put("name", myName);
        req.put("fromName", myName);
        req.put("at", System.currentTimeMillis());
        FirebaseUtils.getRequestsRef(foundUid).child(myUid).setValue(req)
            .addOnSuccessListener(x -> {
                com.callx.app.utils.PushNotify.notifyUser(foundUid, myUid, myName,
                    "request", "Aapko contact request bheji hai");
                Toast.makeText(this, "Request bhej di!", Toast.LENGTH_SHORT).show();
                finish();
            });
    }
}
