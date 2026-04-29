package com.callx.app.utils;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.database.DatabaseReference;
import com.google.firebase.database.FirebaseDatabase;
import java.util.Arrays;
public class FirebaseUtils {
    public static String getCurrentUid() {
        return FirebaseAuth.getInstance().getCurrentUser().getUid();
    }
    public static DatabaseReference getUserRef(String uid) {
        return FirebaseDatabase.getInstance().getReference("users").child(uid);
    }
    public static DatabaseReference getMessagesRef(String chatId) {
        return FirebaseDatabase.getInstance().getReference("messages").child(chatId);
    }
    public static DatabaseReference getContactsRef(String uid) {
        return FirebaseDatabase.getInstance().getReference("contacts").child(uid);
    }
    public static DatabaseReference getRequestsRef(String uid) {
        return FirebaseDatabase.getInstance().getReference("requests").child(uid);
    }
    public static DatabaseReference getCallsRef(String uid) {
        return FirebaseDatabase.getInstance().getReference("calls").child(uid);
    }
    public static String getChatId(String uid1, String uid2) {
        String[] ids = {uid1, uid2};
        Arrays.sort(ids);
        return ids[0] + "_" + ids[1];
    }
}
