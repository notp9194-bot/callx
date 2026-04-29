package com.callx.app.fragments;
import android.content.Intent;
import android.os.Bundle;
import android.view.*;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.RequestsActivity;
import com.callx.app.adapters.ChatListAdapter;
import com.callx.app.models.User;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.ArrayList;
import java.util.List;
public class ChatsFragment extends Fragment {
    private final List<User> contacts = new ArrayList<>();
    private ChatListAdapter adapter;
    private View emptyState, bannerRequests;
    private TextView tvRequestCount, tvRequestSub;
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_chats);
        emptyState     = v.findViewById(R.id.empty_state);
        bannerRequests = v.findViewById(R.id.banner_requests);
        tvRequestCount = v.findViewById(R.id.tv_request_count);
        tvRequestSub   = v.findViewById(R.id.tv_request_sub);
        bannerRequests.setOnClickListener(x ->
            startActivity(new Intent(getContext(), RequestsActivity.class)));
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChatListAdapter(contacts);
        rv.setAdapter(adapter);
        loadContacts();
        loadRequestsCount();
        return v;
    }
    private void loadContacts() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.getContactsRef(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    contacts.clear();
                    for (DataSnapshot c : snap.getChildren()) {
                        User u = c.getValue(User.class);
                        if (u != null) {
                            if (u.uid == null) u.uid = c.getKey();
                            contacts.add(u);
                        }
                    }
                    adapter.notifyDataSetChanged();
                    emptyState.setVisibility(contacts.isEmpty() ? View.VISIBLE : View.GONE);
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
    private void loadRequestsCount() {
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String uid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        FirebaseUtils.getRequestsRef(uid)
            .addValueEventListener(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    long n = snap.getChildrenCount();
                    if (n > 0) {
                        bannerRequests.setVisibility(View.VISIBLE);
                        tvRequestCount.setText(String.valueOf(n));
                        tvRequestSub.setText(n == 1
                            ? "1 nayi request — tap karke dekho"
                            : n + " nayi requests — tap karke dekho");
                    } else {
                        bannerRequests.setVisibility(View.GONE);
                    }
                }
                @Override public void onCancelled(DatabaseError e) {}
            });
    }
}
