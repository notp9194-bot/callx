package com.callx.app.activities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.AlphaAnimation;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.exoplayer.ExoPlayer;
import com.bumptech.glide.Glide;
import com.callx.app.R;
import com.callx.app.databinding.ActivityStatusViewerBinding;
import com.callx.app.models.StatusItem;
import com.callx.app.utils.FirebaseUtils;
import com.google.firebase.database.*;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class StatusViewerActivity extends AppCompatActivity {
    private ActivityStatusViewerBinding binding;
    private final List<StatusItem> items = new ArrayList<>();
    private final List<ProgressBar> segments = new ArrayList<>();
    private int idx = 0;
    private ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable progressRunner;
    private boolean paused = false;
    private long elapsed = 0;
    private long total = 5000;
    private static final long STEP = 30;
    private final SimpleDateFormat fmt = new SimpleDateFormat("HH:mm", Locale.getDefault());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Edge-to-edge dark experience
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS);
        binding = ActivityStatusViewerBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        String ownerUid = getIntent().getStringExtra("ownerUid");
        if (ownerUid == null) { finish(); return; }
        binding.btnCloseStatus.setOnClickListener(v -> closeWithSlide());
        setupGestures();
        setupReply();
        load(ownerUid);
    }

    private void setupGestures() {
        // Tap right zone -> next
        binding.zoneNext.setOnClickListener(v -> nextStep());
        // Tap left zone -> previous
        binding.zonePrev.setOnClickListener(v -> prevStep());

        // Hold (long-press) -> pause; release -> resume.
        // We attach to root + zones via touch listener.
        View.OnTouchListener holdListener = (v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    handler.postDelayed(pauseRun, 220); // small delay to differentiate tap vs hold
                    return false;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(pauseRun);
                    if (paused) resume();
                    return false;
            }
            return false;
        };
        binding.zoneNext.setOnTouchListener(holdListener);
        binding.zonePrev.setOnTouchListener(holdListener);

        // Swipe down -> close (intercepted in dispatchTouchEvent below)
        swipeDetector = new GestureDetector(this,
            new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onFling(MotionEvent e1, MotionEvent e2,
                                                 float vX, float vY) {
                    if (e1 == null || e2 == null) return false;
                    float dy = e2.getY() - e1.getY();
                    if (dy > 180 && Math.abs(vY) > 600) {
                        closeWithSlide();
                        return true;
                    }
                    return false;
                }
            });
    }

    private GestureDetector swipeDetector;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (swipeDetector != null) swipeDetector.onTouchEvent(ev);
        return super.dispatchTouchEvent(ev);
    }

    private final Runnable pauseRun = () -> { paused = true; pause(); };

    private void setupReply() {
        binding.btnSendReply.setOnClickListener(v -> {
            String txt = binding.etReply.getText() == null ? "" : binding.etReply.getText().toString().trim();
            if (TextUtils.isEmpty(txt)) return;
            Toast.makeText(this, "Reply sent", Toast.LENGTH_SHORT).show();
            binding.etReply.setText("");
            InputMethodManager imm = (InputMethodManager)
                getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(binding.etReply.getWindowToken(), 0);
        });
        binding.etReply.setOnFocusChangeListener((v, has) -> {
            if (has) pause(); else resume();
        });
    }

    private void load(String ownerUid) {
        FirebaseUtils.getStatusRef().child(ownerUid)
            .addListenerForSingleValueEvent(new ValueEventListener() {
                @Override public void onDataChange(DataSnapshot snap) {
                    long now = System.currentTimeMillis();
                    for (DataSnapshot c : snap.getChildren()) {
                        StatusItem s = c.getValue(StatusItem.class);
                        if (s == null) continue;
                        if (s.expiresAt != null && s.expiresAt < now) continue;
                        items.add(s);
                    }
                    if (items.isEmpty()) { finish(); return; }
                    StatusItem first = items.get(0);
                    binding.tvOwner.setText(first.ownerName == null ? "Status" : first.ownerName);
                    if (first.ownerPhoto != null && !first.ownerPhoto.isEmpty()) {
                        Glide.with(StatusViewerActivity.this).load(first.ownerPhoto)
                            .circleCrop().into(binding.ivOwner);
                    }
                    buildSegments();
                    showCurrent();
                }
                @Override public void onCancelled(DatabaseError e) { finish(); }
            });
    }

    private void buildSegments() {
        binding.progressContainer.removeAllViews();
        segments.clear();
        int n = items.size();
        for (int i = 0; i < n; i++) {
            ProgressBar p = new ProgressBar(this, null,
                android.R.attr.progressBarStyleHorizontal);
            p.setMax(1000);
            p.setProgress(0);
            p.setProgressDrawable(
                ContextCompat.getDrawable(this, R.drawable.progress_segment_fill));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            lp.setMarginStart(i == 0 ? 0 : (int)(3 * getResources().getDisplayMetrics().density));
            p.setLayoutParams(lp);
            binding.progressContainer.addView(p);
            segments.add(p);
        }
    }

    private void showCurrent() {
        if (idx >= items.size()) { closeWithSlide(); return; }
        // mark previous as full
        for (int i = 0; i < idx && i < segments.size(); i++) segments.get(i).setProgress(1000);
        // reset upcoming
        for (int i = idx + 1; i < segments.size(); i++) segments.get(i).setProgress(0);

        StatusItem s = items.get(idx);
        binding.tvOwnerTime.setText(s.timestamp != null ? fmt.format(new Date(s.timestamp)) : "");
        hideAll();
        // Fade-in transition for the media container
        AlphaAnimation fade = new AlphaAnimation(0f, 1f);
        fade.setDuration(220);
        binding.flMedia.startAnimation(fade);

        if ("text".equals(s.type)) {
            binding.flTextStatus.setVisibility(View.VISIBLE);
            binding.tvTextStatus.setText(s.text == null ? "" : s.text);
            total = 5000;
            startProgress();
        } else if ("video".equals(s.type) && s.mediaUrl != null) {
            binding.player.setVisibility(View.VISIBLE);
            if (player != null) player.release();
            player = new ExoPlayer.Builder(this).build();
            binding.player.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(Uri.parse(s.mediaUrl)));
            player.prepare();
            player.setPlayWhenReady(true);
            total = 15000;
            startProgress();
        } else if ("image".equals(s.type) && s.mediaUrl != null) {
            binding.ivStatus.setVisibility(View.VISIBLE);
            Glide.with(this).load(s.mediaUrl).into(binding.ivStatus);
            total = 5000;
            startProgress();
        } else {
            nextStep();
        }
    }

    private void hideAll() {
        binding.flTextStatus.setVisibility(View.GONE);
        binding.player.setVisibility(View.GONE);
        binding.ivStatus.setVisibility(View.GONE);
    }

    private void startProgress() {
        if (progressRunner != null) handler.removeCallbacks(progressRunner);
        elapsed = 0;
        paused = false;
        if (idx < segments.size()) segments.get(idx).setProgress(0);
        progressRunner = new Runnable() {
            @Override public void run() {
                if (paused) return;
                elapsed += STEP;
                int prog = (int)((elapsed * 1000L) / total);
                if (idx < segments.size()) {
                    segments.get(idx).setProgress(Math.min(1000, prog));
                }
                if (elapsed >= total) { nextStep(); }
                else handler.postDelayed(this, STEP);
            }
        };
        handler.postDelayed(progressRunner, STEP);
    }

    private void pause() {
        paused = true;
        if (player != null) player.setPlayWhenReady(false);
    }
    private void resume() {
        if (!paused) return;
        paused = false;
        if (player != null) player.setPlayWhenReady(true);
        handler.postDelayed(progressRunner, STEP);
    }

    private void nextStep() {
        if (player != null) { player.release(); player = null; }
        if (progressRunner != null) handler.removeCallbacks(progressRunner);
        idx++;
        showCurrent();
    }
    private void prevStep() {
        if (player != null) { player.release(); player = null; }
        if (progressRunner != null) handler.removeCallbacks(progressRunner);
        if (idx > 0) {
            idx--;
            if (idx < segments.size()) segments.get(idx).setProgress(0);
        }
        showCurrent();
    }

    private void closeWithSlide() {
        finish();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down_out);
    }

    @Override public void onBackPressed() { closeWithSlide(); }

    @Override
    protected void onDestroy() {
        if (player != null) { player.release(); player = null; }
        if (progressRunner != null) handler.removeCallbacks(progressRunner);
        handler.removeCallbacks(pauseRun);
        super.onDestroy();
    }
}
