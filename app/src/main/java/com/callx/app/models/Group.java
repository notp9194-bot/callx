package com.callx.app.models;
import java.util.HashMap;
import java.util.Map;
public class Group {
    public String id;
    public String name;
    public String iconUrl;
    public String createdBy;
    public Long createdAt;
    public String lastMessage;
    public Long lastMessageAt;
    public Map<String, Boolean> members = new HashMap<>();
    public Group() {}
}
