package com.callx.app.activities;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import com.callx.app.R;
public class IncomingCallActivity extends AppCompatActivity {
    private String fromUid, fromName;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
            KeyguardManager km = (KeyguardManager) getSystemService(KEYGUARD_SERVICE);
            if (km != null) km.requestDismissKeyguard(this, null);
        } else {
            getWindow().addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_incoming_call);
        fromUid  = getIntent().getStringExtra("fromUid");
        fromName = getIntent().getStringExtra("fromName");
        TextView tvName = findViewById(R.id.tv_caller_name);
        TextView tvSub  = findViewById(R.id.tv_caller_sub);
        tvName.setText(fromName != null ? fromName : "Unknown");
        tvSub.setText("Incoming CallX...");
        Button btnAccept = findViewById(R.id.btn_accept);
        Button btnReject = findViewById(R.id.btn_reject);
        btnAccept.setOnClickListener(v -> {
            cancelNotif();
            Intent i = new Intent(this, CallActivity.class);
            i.putExtra("partnerUid", fromUid);
            i.putExtra("partnerName", fromName);
            i.putExtra("isCaller", false);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            finish();
        });
        btnReject.setOnClickListener(v -> {
            cancelNotif();
            finish();
        });
    }
    private void cancelNotif() {
        NotificationManager nm =
            (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.cancel(1001);
    }
}
