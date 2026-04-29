package com.callx.app.adapters;
import android.content.Intent;
import android.net.Uri;
import android.view.*;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.models.Message;
import java.util.List;
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MsgViewHolder> {
    private final List<Message> messages;
    private final String currentUid;
    private static final int SENT = 1, RECEIVED = 2;
    public MessageAdapter(List<Message> messages, String currentUid) {
        this.messages = messages; this.currentUid = currentUid;
    }
    @Override public int getItemViewType(int pos) {
        Message m = messages.get(pos);
        return (m.senderId != null && m.senderId.equals(currentUid)) ? SENT : RECEIVED;
    }
    @NonNull @Override
    public MsgViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == SENT
            ? R.layout.item_message_sent : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new MsgViewHolder(v);
    }
    @Override public void onBindViewHolder(@NonNull MsgViewHolder h, int pos) {
        Message m = messages.get(pos);
        boolean isImage = "image".equals(m.type)
            && m.imageUrl != null && !m.imageUrl.isEmpty();
        if (isImage) {
            h.tvMessage.setVisibility(View.GONE);
            h.ivImage.setVisibility(View.VISIBLE);
            Glide.with(h.itemView.getContext())
                .load(m.imageUrl)
                .placeholder(android.R.drawable.ic_menu_gallery)
                .error(android.R.drawable.stat_notify_error)
                .into(h.ivImage);
            h.ivImage.setOnClickListener(v -> {
                try {
                    v.getContext().startActivity(
                        new Intent(Intent.ACTION_VIEW, Uri.parse(m.imageUrl)));
                } catch (Exception ignored) {}
            });
        } else {
            h.ivImage.setVisibility(View.GONE);
            h.tvMessage.setVisibility(View.VISIBLE);
            h.tvMessage.setText(m.text != null ? m.text : "");
        }
    }
    @Override public int getItemCount() { return messages.size(); }
    static class MsgViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;
        ImageView ivImage;
        MsgViewHolder(View v) {
            super(v);
            tvMessage = v.findViewById(R.id.tv_message);
            ivImage   = v.findViewById(R.id.iv_image);
        }
    }
}
