package com.weeklyroster.service.sms.provider;

import com.weeklyroster.entity.SmsMessageType;
import com.weeklyroster.service.sms.SmsDeliveryResult;

public interface SmsProvider {
    String getProviderName();
    boolean isConfigured();
    boolean isRealTelecom();
    SmsDeliveryResult send(String clean10DigitPhone, String message, SmsMessageType messageType, String dltTemplateId);
}
