package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import androidx.fragment.app.Fragment;
import com.callx.app.R;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
public class UpdatesFragment extends Fragment {
    private LinearLayout llContainer;
    private String currentUid;
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_updates, container, false);
        llContainer = view.findViewById(R.id.ll_requests_container);
        currentUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        loadRequests();
        return view;
    }
    private void loadRequests() {
        FirebaseDatabase.getInstance().getReference("requests").child(currentUid)
            .addValueEventListener(new ValueEventListener() {
                public void onDataChange(DataSnapshot snapshot) {
                    llContainer.removeAllViews();
                    if (!snapshot.exists()) {
                        TextView tv = new TextView(getContext());
                        tv.setText("Koi contact request nahi hai abhi");
                        tv.setPadding(32, 48, 32, 32);
                        tv.setTextColor(0xFF667781);
                        llContainer.addView(tv);
                        return;
                    }
                    for (DataSnapshot req : snapshot.getChildren()) {
                        String fromUid  = req.child("fromUid").getValue(String.class);
                        String fromName = req.child("fromName").getValue(String.class);
                        if (fromUid == null) continue;
                        addRequestCard(fromUid, fromName != null ? fromName : "Unknown");
                    }
                }
                public void onCancelled(DatabaseError e) {}
            });
    }
    private void addRequestCard(String fromUid, String fromName) {
        View card = LayoutInflater.from(getContext())
            .inflate(R.layout.item_request, llContainer, false);
        TextView tvName  = card.findViewById(R.id.tv_name);
        Button btnAccept = card.findViewById(R.id.btn_accept);
        Button btnReject = card.findViewById(R.id.btn_reject);
        tvName.setText(fromName);
        btnAccept.setOnClickListener(v -> acceptRequest(fromUid, fromName));
        btnReject.setOnClickListener(v -> rejectRequest(fromUid));
        llContainer.addView(card);
    }
    private void acceptRequest(String fromUid, String fromName) {
        DatabaseReference db = FirebaseDatabase.getInstance().getReference();
        String myName = FirebaseAuth.getInstance().getCurrentUser().getDisplayName();
        if (myName == null || myName.isEmpty()) myName = "CallX User";
        // Dono ke contacts mein add karo
        Map<String, Object> them = new HashMap<>();
        them.put("uid", fromUid); them.put("name", fromName); them.put("emoji", "😊");
        db.child("contacts").child(currentUid).child(fromUid).setValue(them);
        Map<String, Object> me = new HashMap<>();
        me.put("uid", currentUid); me.put("name", myName); me.put("emoji", "😊");
        db.child("contacts").child(fromUid).child(currentUid).setValue(me);
        // Request delete
        db.child("requests").child(currentUid).child(fromUid).removeValue();
        Toast.makeText(getContext(),
            fromName + " contact mein add ho gaya! ✅", Toast.LENGTH_SHORT).show();
    }
    private void rejectRequest(String fromUid) {
        FirebaseDatabase.getInstance().getReference("requests")
            .child(currentUid).child(fromUid).removeValue();
        Toast.makeText(getContext(), "Request reject kar di", Toast.LENGTH_SHORT).show();
    }
}
