package com.weeklyroster.service.sms;

public interface SmsService {
    SmsDeliveryResult sendSms(String toPhoneNumber, String message);
    boolean isConfigured();
    String getProviderName();
}
