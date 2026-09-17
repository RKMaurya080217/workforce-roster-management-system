package com.weeklyroster.service.sms;

import com.weeklyroster.entity.SmsDeliveryStatus;

public record SmsDeliveryResult(
        boolean success,
        String messageId,
        String provider,
        String errorMessage,
        SmsDeliveryStatus deliveryStatus
) {
    public SmsDeliveryResult(boolean success, String messageId, String provider, String errorMessage) {
        this(success, messageId, provider, errorMessage,
                success ? SmsDeliveryStatus.REQUEST_ACCEPTED : SmsDeliveryStatus.REQUEST_FAILED);
    }

    public static SmsDeliveryResult success(String messageId, String provider) {
        return new SmsDeliveryResult(true, messageId, provider, null, SmsDeliveryStatus.REQUEST_ACCEPTED);
    }

    public static SmsDeliveryResult failure(String provider, String errorMessage) {
        return new SmsDeliveryResult(false, null, provider, errorMessage, SmsDeliveryStatus.REQUEST_FAILED);
    }

    public static SmsDeliveryResult failure(String provider, String errorMessage, SmsDeliveryStatus status) {
        return new SmsDeliveryResult(false, null, provider, errorMessage, status);
    }

    public static SmsDeliveryResult simulated(String provider, String messageId) {
        return new SmsDeliveryResult(true, messageId, provider, null, SmsDeliveryStatus.SIMULATED_LOG);
    }

    public static SmsDeliveryResult skipped(String provider, String reason, SmsDeliveryStatus status) {
        return new SmsDeliveryResult(false, null, provider, reason, status);
    }
}
