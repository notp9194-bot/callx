package com.callx.app.activities;
import android.os.Bundle;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.R;
import com.callx.app.models.User;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.HashMap;
import java.util.Map;
public class SearchActivity extends AppCompatActivity {
    private EditText etSearch;
    private Button btnSearch;
    private TextView tvResult, tvResultId, tvStatus;
    private LinearLayout llResult;
    private String foundUid, foundName, foundCallxId;
    private String currentUid;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_search);
        // Toolbar back button
        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        etSearch   = findViewById(R.id.et_search_id);
        btnSearch  = findViewById(R.id.btn_search);
        tvResult   = findViewById(R.id.tv_result_name);
        tvResultId = findViewById(R.id.tv_result_id);
        tvStatus   = findViewById(R.id.tv_status);
        llResult   = findViewById(R.id.ll_result);
        btnSearch.setOnClickListener(v -> searchUser());
        findViewById(R.id.btn_send_request).setOnClickListener(v -> sendRequest());
    }
    private void searchUser() {
        String callxId = etSearch.getText().toString().trim();
        if (callxId.isEmpty()) {
            Toast.makeText(this, "CallX ID daalo", Toast.LENGTH_SHORT).show();
            return;
        }
        llResult.setVisibility(View.GONE);
        tvStatus.setText("Dhundh raha hai...");
        tvStatus.setVisibility(View.VISIBLE);
        FirebaseDatabase.getInstance("https://sathix-97a76-default-rtdb.firebaseio.com").getReference("users")
            .orderByChild("callxId").equalTo(callxId)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                public void onDataChange(DataSnapshot snapshot) {
                    if (!snapshot.exists()) {
                        tvStatus.setText("❌ User nahi mila");
                        return;
                    }
                    for (DataSnapshot child : snapshot.getChildren()) {
                        User user = child.getValue(User.class);
                        foundUid     = child.getKey();
                        foundName    = user != null ? user.name : "Unknown";
                        foundCallxId = user != null ? user.callxId : callxId;
                    }
                    if (currentUid.equals(foundUid)) {
                        tvStatus.setText("Yeh aap khud ho!");
                        return;
                    }
                    tvStatus.setVisibility(View.GONE);
                    tvResult.setText("😊  " + foundName);
                    tvResultId.setText(foundCallxId);
                    llResult.setVisibility(View.VISIBLE);
                }
                public void onCancelled(DatabaseError e) {
                    tvStatus.setText("Error: " + e.getMessage());
                }
            });
    }
    private void sendRequest() {
        if (foundUid == null) return;
        DatabaseReference db = FirebaseDatabase.getInstance("https://sathix-97a76-default-rtdb.firebaseio.com").getReference();
        // Already contact check
        db.child("contacts").child(currentUid).child(foundUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                public void onDataChange(DataSnapshot snap) {
                    if (snap.exists()) {
                        Toast.makeText(SearchActivity.this,
                            "Pehle se contact hai!", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    // Already pending request check
                    db.child("requests").child(foundUid).child(currentUid)
                        .addListenerForSingleValueEvent(new ValueEventListener() {
                            public void onDataChange(DataSnapshot s2) {
                                if (s2.exists()) {
                                    Toast.makeText(SearchActivity.this,
                                        "Request pehle se bhej di hai", Toast.LENGTH_SHORT).show();
                                    return;
                                }
                                String myName = FirebaseAuth.getInstance()
                                    .getCurrentUser().getDisplayName();
                                if (myName == null || myName.isEmpty()) myName = "CallX User";
                                Map<String, Object> req = new HashMap<>();
                                req.put("fromUid", currentUid);
                                req.put("fromName", myName);
                                req.put("timestamp", System.currentTimeMillis());
                                db.child("requests").child(foundUid).child(currentUid)
                                    .setValue(req)
                                    .addOnSuccessListener(x -> {
                                        Toast.makeText(SearchActivity.this,
                                            "✅ Request bhej di " + foundName + " ko!",
                                            Toast.LENGTH_LONG).show();
                                        finish();
                                    });
                            }
                            public void onCancelled(DatabaseError e) {}
                        });
                }
                public void onCancelled(DatabaseError e) {}
            });
    }
    @Override
    public boolean onSupportNavigateUp() { finish(); return true; }
}
