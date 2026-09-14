package com.weeklyroster.dto;

public class VisitorStatsResponse {
    private boolean success;
    private long totalVisits;
    private long onlineNow;
    private String message;

    public VisitorStatsResponse() {}

    public VisitorStatsResponse(boolean success, long totalVisits, long onlineNow, String message) {
        this.success = success;
        this.totalVisits = totalVisits;
        this.onlineNow = onlineNow;
        this.message = message;
    }

    public static VisitorStatsResponse of(long totalVisits, long onlineNow) {
        return new VisitorStatsResponse(true, totalVisits, onlineNow, "OK");
    }

    public static VisitorStatsResponse error(String message) {
        return new VisitorStatsResponse(false, 0L, 0L, message);
    }

    public boolean isSuccess() { return success; }
    public void setSuccess(boolean success) { this.success = success; }

    public long getTotalVisits() { return totalVisits; }
    public void setTotalVisits(long totalVisits) { this.totalVisits = totalVisits; }

    public long getOnlineNow() { return onlineNow; }
    public void setOnlineNow(long onlineNow) { this.onlineNow = onlineNow; }

    public String getMessage() { return message; }
    public void setMessage(String message) { this.message = message; }
}