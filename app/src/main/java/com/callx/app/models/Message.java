package com.callx.app.models;
public class Message {
    // type: "text" | "image"
    public String senderId, text, type, imageUrl;
    public long timestamp;
    public Message() {}
    public Message(String senderId, String text, long timestamp) {
        this.senderId = senderId;
        this.text = text;
        this.timestamp = timestamp;
        this.type = "text";
    }
    public static Message image(String senderId, String imageUrl, long timestamp) {
        Message m = new Message();
        m.senderId = senderId;
        m.imageUrl = imageUrl;
        m.type = "image";
        m.timestamp = timestamp;
        return m;
    }
}
