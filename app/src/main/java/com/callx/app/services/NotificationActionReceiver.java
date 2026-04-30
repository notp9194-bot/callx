package com.callx.app.services;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import androidx.core.app.RemoteInput;
import com.callx.app.utils.Constants;
import com.callx.app.utils.FirebaseUtils;
import com.callx.app.utils.PushNotify;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.ServerValue;
import java.util.HashMap;
import java.util.Map;
public class NotificationActionReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null) return;
        String chatId      = intent.getStringExtra(Constants.EXTRA_CHAT_ID);
        String partnerUid  = intent.getStringExtra(Constants.EXTRA_PARTNER_UID);
        String partnerName = intent.getStringExtra(Constants.EXTRA_PARTNER_NAME);
        int notifId        = intent.getIntExtra(Constants.EXTRA_NOTIF_ID, 0);
        if (FirebaseAuth.getInstance().getCurrentUser() == null) return;
        String myUid  = FirebaseAuth.getInstance().getCurrentUser().getUid();
        String myName = FirebaseUtils.getCurrentName();
        NotificationManager nm = (NotificationManager)
            context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (Constants.ACTION_MARK_READ.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.getContactsRef(myUid).child(partnerUid)
                    .child("unread").setValue(0);
            }
            if (nm != null) nm.cancel(notifId);
            return;
        }
        if (Constants.ACTION_MUTE.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("muted")
                    .child(myUid).child(partnerUid).setValue(true);
            }
            if (nm != null) nm.cancel(notifId);
            return;
        }
        if (Constants.ACTION_BLOCK.equals(action)) {
            if (partnerUid != null) {
                FirebaseUtils.db().getReference("blocked")
                    .child(myUid).child(partnerUid).setValue(true);
            }
            if (nm != null) nm.cancel(notifId);
            return;
        }
        if (Constants.ACTION_REPLY.equals(action)) {
            Bundle remote = RemoteInput.getResultsFromIntent(intent);
            if (remote == null) return;
            CharSequence reply = remote.getCharSequence(Constants.KEY_TEXT_REPLY);
            if (reply == null) return;
            String text = reply.toString().trim();
            if (text.isEmpty() || chatId == null || partnerUid == null) return;
            DatabaseReference msgRef = FirebaseUtils.getMessagesRef(chatId).push();
            Map<String, Object> m = new HashMap<>();
            m.put("id",         msgRef.getKey());
            m.put("senderId",   myUid);
            m.put("senderName", myName);
            m.put("text",       text);
            m.put("type",       "text");
            m.put("timestamp",  System.currentTimeMillis());
            msgRef.setValue(m);
            // Update last message + unread counters (mine reset, partner +1)
            Map<String, Object> meSide = new HashMap<>();
            meSide.put("lastMessage",   text);
            meSide.put("lastMessageAt", System.currentTimeMillis());
            meSide.put("unread",        0);
            FirebaseUtils.getContactsRef(myUid).child(partnerUid)
                .updateChildren(meSide);
            Map<String, Object> partnerSide = new HashMap<>();
            partnerSide.put("lastMessage",   text);
            partnerSide.put("lastMessageAt", System.currentTimeMillis());
            partnerSide.put("unread",        ServerValue.increment(1));
            FirebaseUtils.getContactsRef(partnerUid).child(myUid)
                .updateChildren(partnerSide);
            // Push to partner
            PushNotify.notifyMessage(partnerUid, myUid, myName, chatId,
                msgRef.getKey(), text, "message", "");
            if (nm != null) nm.cancel(notifId);
        }
    }
}
