package com.weeklyroster.service.sms;

public record SmsDeliveryResult(
        boolean success,
        String messageId,
        String provider,
        String errorMessage
) {
    public static SmsDeliveryResult success(String messageId, String provider) {
        return new SmsDeliveryResult(true, messageId, provider, null);
    }

    public static SmsDeliveryResult failure(String provider, String errorMessage) {
        return new SmsDeliveryResult(false, null, provider, errorMessage);
    }
}
