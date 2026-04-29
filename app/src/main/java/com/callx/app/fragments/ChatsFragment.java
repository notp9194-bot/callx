package com.callx.app.fragments;
import android.os.Bundle;
import android.view.*;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.adapters.ContactAdapter;
import com.callx.app.models.User;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.*;
import java.util.*;
public class ChatsFragment extends Fragment {
    private RecyclerView rv;
    private ContactAdapter adapter;
    private List<User> contacts = new ArrayList<>();
    private String currentUid;
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_chats, container, false);
        rv = view.findViewById(R.id.rv_chats);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new ContactAdapter(contacts);
        rv.setAdapter(adapter);
        currentUid = FirebaseAuth.getInstance().getCurrentUser().getUid();
        loadContacts();
        return view;
    }
    private void loadContacts() {
        FirebaseDatabase.getInstance("https://sathix-97a76-default-rtdb.firebaseio.com").getReference("contacts").child(currentUid)
            .addValueEventListener(new ValueEventListener() {
                public void onDataChange(DataSnapshot snapshot) {
                    contacts.clear();
                    for (DataSnapshot child : snapshot.getChildren()) {
                        User user = child.getValue(User.class);
                        if (user != null) contacts.add(user);
                    }
                    adapter.notifyDataSetChanged();
                }
                public void onCancelled(DatabaseError e) {}
            });
    }
}
