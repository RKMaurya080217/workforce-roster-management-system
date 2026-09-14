package com.weeklyroster.dto;

public class VisitorHeartbeatRequest {
    private String visitorId;

    public VisitorHeartbeatRequest() {}

    public VisitorHeartbeatRequest(String visitorId) {
        this.visitorId = visitorId;
    }

    public String getVisitorId() { return visitorId; }
    public void setVisitorId(String visitorId) { this.visitorId = visitorId; }
}