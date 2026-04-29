package com.callx.app.adapters;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.callx.app.R;
import com.callx.app.models.Message;
import java.util.List;
public class MessageAdapter extends RecyclerView.Adapter<MessageAdapter.MsgViewHolder> {
    private List<Message> messages;
    private String currentUid;
    private static final int SENT = 1, RECEIVED = 2;
    public MessageAdapter(List<Message> messages, String currentUid) {
        this.messages = messages; this.currentUid = currentUid;
    }
    @Override public int getItemViewType(int pos) {
        return messages.get(pos).senderId.equals(currentUid) ? SENT : RECEIVED;
    }
    @NonNull @Override
    public MsgViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layout = viewType == SENT ? R.layout.item_message_sent : R.layout.item_message_received;
        View v = LayoutInflater.from(parent.getContext()).inflate(layout, parent, false);
        return new MsgViewHolder(v);
    }
    @Override public void onBindViewHolder(@NonNull MsgViewHolder h, int pos) {
        h.tvMessage.setText(messages.get(pos).text);
    }
    @Override public int getItemCount() { return messages.size(); }
    static class MsgViewHolder extends RecyclerView.ViewHolder {
        TextView tvMessage;
        MsgViewHolder(View v) { super(v); tvMessage = v.findViewById(R.id.tv_message); }
    }
}
