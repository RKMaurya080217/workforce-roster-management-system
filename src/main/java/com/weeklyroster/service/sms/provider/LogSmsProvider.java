package com.weeklyroster.service.sms.provider;

import com.weeklyroster.entity.SmsMessageType;
import com.weeklyroster.service.sms.SmsDeliveryResult;
import com.weeklyroster.util.PhoneUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

public class LogSmsProvider implements SmsProvider {

    private static final Logger log = LoggerFactory.getLogger(LogSmsProvider.class);

    @Override
    public String getProviderName() {
        return "LOG";
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public boolean isRealTelecom() {
        return false;
    }

    @Override
    public SmsDeliveryResult send(String clean10DigitPhone, String message, SmsMessageType messageType, String dltTemplateId) {
        String masked = PhoneUtils.maskPhone(clean10DigitPhone);
        log.info("[WRMS SMS - SIMULATED/LOG] Dispatching [{}] to {}: \"{}\"",
                messageType != null ? messageType : "SMS", masked, message);
        String mockId = "SIM-SMS-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return SmsDeliveryResult.simulated(getProviderName(), mockId);
    }
}
