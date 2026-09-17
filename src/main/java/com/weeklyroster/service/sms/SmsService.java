package com.weeklyroster.service.sms;

import com.weeklyroster.dto.response.SmsDiagnosticsResponse;
import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.SmsDeliveryLog;

import java.util.List;

public interface SmsService {

    SmsDeliveryResult sendSms(String toPhoneNumber, String message);

    SmsDeliveryResult sendRosterSms(RosterCycle cycle, Employee employee, boolean isFinal, String dateRange);

    SmsDeliveryResult sendAdminTestSms(String targetPhoneNumber, Long employeeId);

    boolean isConfigured();

    boolean isRealTelecomConfigured();

    String getProviderName();

    SmsDiagnosticsResponse getDiagnostics();

    List<SmsDeliveryLog> getRecentLogs();
}
