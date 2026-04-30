package com.callx.app.adapters;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.Intent;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.activities.StatusViewerActivity;
import com.callx.app.models.StatusItem;
import de.hdodenhof.circleimageview.CircleImageView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
public class StatusListAdapter extends RecyclerView.Adapter<StatusListAdapter.VH> {
    private final List<StatusItem> entries;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());
    private final Set<String> seenUids = new HashSet<>();
    public StatusListAdapter(List<StatusItem> entries) { this.entries = entries; }
    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
            .inflate(R.layout.item_status, parent, false);
        return new VH(v);
    }
    @Override public void onBindViewHolder(@NonNull VH h, int pos) {
        StatusItem s = entries.get(pos);
        Context ctx = h.itemView.getContext();
        h.tvName.setText(s.ownerName == null ? "Status" : s.ownerName);
        h.tvTime.setText(s.timestamp != null ? fmt.format(new Date(s.timestamp)) : "");

        // Profile avatar
        if (s.ownerPhoto != null && !s.ownerPhoto.isEmpty()) {
            Glide.with(ctx).load(s.ownerPhoto).circleCrop().into(h.ivAvatar);
        } else h.ivAvatar.setImageResource(R.drawable.ic_person);

        // Background preview (status media if image, else placeholder gradient)
        if ("image".equals(s.type) && s.mediaUrl != null && !s.mediaUrl.isEmpty()) {
            Glide.with(ctx).load(s.mediaUrl).centerCrop().into(h.ivBg);
        } else if ("video".equals(s.type) && s.mediaUrl != null && !s.mediaUrl.isEmpty()) {
            Glide.with(ctx).load(s.mediaUrl).centerCrop().into(h.ivBg);
        } else {
            h.ivBg.setImageResource(R.drawable.status_card_placeholder);
        }

        // Ring color: green = unseen, grey = seen
        boolean seen = s.ownerUid != null && seenUids.contains(s.ownerUid);
        h.ring.setBackgroundResource(seen
            ? R.drawable.circle_status_seen
            : R.drawable.circle_status_unseen);

        // Card click: scale animation + open viewer
        h.itemView.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    animateScale(v, 0.94f);
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    animateScale(v, 1.0f);
                    break;
            }
            return false; // let click event pass
        });
        h.itemView.setOnClickListener(v -> {
            if (s.ownerUid != null) seenUids.add(s.ownerUid);
            h.ring.setBackgroundResource(R.drawable.circle_status_seen);
            Intent i = new Intent(ctx, StatusViewerActivity.class);
            i.putExtra("ownerUid", s.ownerUid);
            ctx.startActivity(i);
            if (ctx instanceof android.app.Activity) {
                ((android.app.Activity) ctx).overridePendingTransition(
                    R.anim.slide_up_in, android.R.anim.fade_out);
            }
        });
    }
    private void animateScale(View v, float to) {
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
            ObjectAnimator.ofFloat(v, "scaleX", to),
            ObjectAnimator.ofFloat(v, "scaleY", to)
        );
        set.setDuration(140);
        set.setInterpolator(new DecelerateInterpolator());
        set.start();
    }
    @Override public int getItemCount() { return entries.size(); }
    static class VH extends RecyclerView.ViewHolder {
        TextView tvName, tvTime;
        CircleImageView ivAvatar;
        ImageView ivBg;
        View ring;
        VH(View v) {
            super(v);
            tvName   = v.findViewById(R.id.tv_name);
            tvTime   = v.findViewById(R.id.tv_time);
            ivAvatar = v.findViewById(R.id.iv_avatar);
            ivBg     = v.findViewById(R.id.iv_bg);
            ring     = v.findViewById(R.id.ring);
        }
    }
}
