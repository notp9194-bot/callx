package com.callx.app.utils;
public class Constants {
    public static final String DB_URL =
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
    public static final String SERVER_URL =
        "https://callx-server.onrender.com";
    public static final String CLOUDINARY_CLOUD_NAME = "dvqqgqdls";
    // Notification channels
    public static final String CHANNEL_CALLS    = "callx_calls";
    public static final String CHANNEL_MESSAGES = "callx_messages";
    public static final String CHANNEL_GROUPS   = "callx_groups";
    public static final String CHANNEL_STATUS   = "callx_status";
    public static final String CHANNEL_REQUESTS = "callx_requests";
    public static final int HTTP_TIMEOUT_MS = 20000;
    public static final long STATUS_TTL_MS = 24L * 60 * 60 * 1000;
    // Notification action intents (handled by NotificationActionReceiver)
    public static final String ACTION_REPLY      = "com.callx.app.ACTION_REPLY";
    public static final String ACTION_MARK_READ  = "com.callx.app.ACTION_MARK_READ";
    public static final String ACTION_MUTE       = "com.callx.app.ACTION_MUTE";
    public static final String ACTION_BLOCK      = "com.callx.app.ACTION_BLOCK";
    public static final String EXTRA_CHAT_ID      = "extra_chat_id";
    public static final String EXTRA_PARTNER_UID  = "extra_partner_uid";
    public static final String EXTRA_PARTNER_NAME = "extra_partner_name";
    public static final String EXTRA_NOTIF_ID     = "extra_notif_id";
    public static final String KEY_TEXT_REPLY     = "key_text_reply";
    // Online window — last seen within this many ms => Online
    public static final long ONLINE_WINDOW_MS = 60_000L;
    private Constants() {}
}
