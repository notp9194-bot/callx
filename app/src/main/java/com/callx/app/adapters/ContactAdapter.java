package com.callx.app.adapters;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.ChatActivity;
import com.callx.app.models.User;
import java.util.List;
public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ContactVH> {
    private List<User> users;
    public ContactAdapter(List<User> users) { this.users = users; }
    @NonNull @Override
    public ContactVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_contact, parent, false);
        return new ContactVH(v);
    }
    @Override public void onBindViewHolder(@NonNull ContactVH h, int pos) {
        User user = users.get(pos);
        h.tvName.setText(user.name);
        h.tvAvatar.setText(user.emoji != null ? user.emoji : "😊");
        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(v.getContext(), ChatActivity.class);
            i.putExtra("partnerUid", user.uid);
            i.putExtra("partnerName", user.name);
            v.getContext().startActivity(i);
        });
    }
    @Override public int getItemCount() { return users.size(); }
    static class ContactVH extends RecyclerView.ViewHolder {
        TextView tvName, tvAvatar, tvLastMessage, tvUnread;
        ContactVH(View v) {
            super(v);
            tvAvatar = v.findViewById(R.id.tv_avatar);
            tvName = v.findViewById(R.id.tv_name);
            tvLastMessage = v.findViewById(R.id.tv_last_message);
            tvUnread = v.findViewById(R.id.tv_unread);
        }
    }
}
