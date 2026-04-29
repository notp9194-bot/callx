package com.callx.app.models;
public class User {
    public String uid, name, emoji, callxId, email;
    public User() {}
    public User(String uid, String name, String emoji, String callxId, String email) {
        this.uid = uid; this.name = name; this.emoji = emoji;
        this.callxId = callxId; this.email = email;
    }
}
