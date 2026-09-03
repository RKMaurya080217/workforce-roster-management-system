package com.weeklyroster.service.email;

/**
 * Standardized delivery result returned by any EmailProvider.
 */
public class EmailDeliveryResult {
    private final boolean success;
    private final String provider;
    private final String messageId;
    private final String errorMessage;
    private final int httpStatus;

    private EmailDeliveryResult(boolean success, String provider, String messageId, String errorMessage, int httpStatus) {
        this.success = success;
        this.provider = provider;
        this.messageId = messageId;
        this.errorMessage = errorMessage;
        this.httpStatus = httpStatus;
    }

    public static EmailDeliveryResult success(String provider, String messageId) {
        return new EmailDeliveryResult(true, provider, messageId, null, 200);
    }

    public static EmailDeliveryResult failure(String provider, String errorMessage, int httpStatus) {
        return new EmailDeliveryResult(false, provider, null, errorMessage, httpStatus);
    }

    public boolean isSuccess() { return success; }
    public String getProvider() { return provider; }
    public String getMessageId() { return messageId; }
    public String getErrorMessage() { return errorMessage; }
    public int getHttpStatus() { return httpStatus; }
}
