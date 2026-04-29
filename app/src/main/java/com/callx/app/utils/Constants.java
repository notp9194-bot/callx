package com.callx.app.utils;
public class Constants {
    // Firebase Realtime DB
    public static final String DB_URL =
        "https://sathix-97a76-default-rtdb.asia-southeast1.firebasedatabase.app";
    // Render Node server
    public static final String SERVER_URL =
        "https://callx-server.onrender.com";
    // Cloudinary (public — secret server-side)
    public static final String CLOUDINARY_CLOUD_NAME = "dvqqgqdls";
    public static final String CLOUDINARY_UPLOAD_URL =
        "https://api.cloudinary.com/v1_1/" + CLOUDINARY_CLOUD_NAME + "/auto/upload";
    // Notification channels
    public static final String CHANNEL_CALLS    = "callx_calls";
    public static final String CHANNEL_MESSAGES = "callx_messages";
    // Networking
    public static final int HTTP_TIMEOUT_MS = 15000;
    private Constants() {}
}
