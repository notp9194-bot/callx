package com.callx.app.adapters;
import android.content.Intent;
import android.view.*;
import android.widget.*;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.activities.CallActivity;
import com.callx.app.activities.ChatActivity;
import com.callx.app.models.User;
import java.util.List;
public class ContactAdapter extends RecyclerView.Adapter<ContactAdapter.ContactVH> {
    private List<User> users;
    public ContactAdapter(List<User> users) { this.users = users; }
    @NonNull @Override
    public ContactVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_contact, parent, false);
        return new ContactVH(v);
    }
    @Override
    public void onBindViewHolder(@NonNull ContactVH h, int pos) {
        User user = users.get(pos);
        h.tvName.setText(user.name);
        h.tvAvatar.setText(user.emoji != null ? user.emoji : "😊");
        // Chat kholo
        h.itemView.setOnClickListener(v -> {
            Intent i = new Intent(v.getContext(), ChatActivity.class);
            i.putExtra("partnerUid", user.uid);
            i.putExtra("partnerName", user.name);
            v.getContext().startActivity(i);
        });
        // Call karo
        h.btnCall.setOnClickListener(v -> {
            Intent i = new Intent(v.getContext(), CallActivity.class);
            i.putExtra("partnerUid", user.uid);
            i.putExtra("partnerName", user.name);
            i.putExtra("isCaller", true);
            v.getContext().startActivity(i);
        });
    }
    @Override public int getItemCount() { return users.size(); }
    static class ContactVH extends RecyclerView.ViewHolder {
        TextView tvName, tvAvatar;
        ImageButton btnCall;
        ContactVH(View v) {
            super(v);
            tvAvatar = v.findViewById(R.id.tv_avatar);
            tvName   = v.findViewById(R.id.tv_name);
            btnCall  = v.findViewById(R.id.btn_call);
        }
    }
}
