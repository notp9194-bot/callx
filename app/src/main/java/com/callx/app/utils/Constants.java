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
    public static final int HTTP_TIMEOUT_MS = 20000;
    public static final long STATUS_TTL_MS = 24L * 60 * 60 * 1000;
    private Constants() {}
}
