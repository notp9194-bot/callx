package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import androidx.annotation.NonNull;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
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
    private View emptyState;
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup parent, Bundle s) {
        View v = inflater.inflate(R.layout.fragment_chats, parent, false);
        RecyclerView rv = v.findViewById(R.id.rv_chats);
        emptyState = v.findViewById(R.id.empty_state);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ChatListAdapter(contacts);
        rv.setAdapter(adapter);
        loadContacts();
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
}
