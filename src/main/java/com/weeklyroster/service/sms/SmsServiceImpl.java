package com.weeklyroster.service.sms;

import com.weeklyroster.dto.response.SmsDiagnosticsResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.SmsDeliveryLogRepository;
import com.weeklyroster.service.sms.provider.Fast2SmsProvider;
import com.weeklyroster.service.sms.provider.GenericRestSmsProvider;
import com.weeklyroster.service.sms.provider.LogSmsProvider;
import com.weeklyroster.service.sms.provider.SmsProvider;
import com.weeklyroster.util.PhoneUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Set;

@Service
public class SmsServiceImpl implements SmsService {

    private static final Logger log = LoggerFactory.getLogger(SmsServiceImpl.class);

    private final boolean enabled;
    private final String provider;
    private final String apiKey;
    private final String apiSecret;
    private final String senderId;
    private final String endpointUrl;
    private final String dltEntityId;
    private final String dltTemplateIdFinal;
    private final String dltTemplateIdTentative;
    private final String dltTemplateIdAdminTest;

    private final SmsDeliveryLogRepository smsDeliveryLogRepository;
    private final EmployeeRepository employeeRepository;
    private final HttpClient httpClient;

    private SmsProvider activeProvider;

    @Autowired
    public SmsServiceImpl(
            @Value("${sms.enabled:true}") boolean enabled,
            @Value("${sms.provider:${SMS_PROVIDER:LOG}}") String provider,
            @Value("${sms.api.key:${SMS_API_KEY:}}") String apiKey,
            @Value("${sms.api.secret:${SMS_API_SECRET:}}") String apiSecret,
            @Value("${sms.sender.id:${SMS_SENDER_ID:WRMS}}") String senderId,
            @Value("${sms.endpoint.url:${SMS_ENDPOINT_URL:}}") String endpointUrl,
            @Value("${sms.dlt.entity.id:${SMS_DLT_ENTITY_ID:}}") String dltEntityId,
            @Value("${sms.dlt.template.id.final:${SMS_DLT_TEMPLATE_ID_FINAL:}}") String dltTemplateIdFinal,
            @Value("${sms.dlt.template.id.tentative:${SMS_DLT_TEMPLATE_ID_TENTATIVE:}}") String dltTemplateIdTentative,
            @Value("${sms.dlt.template.id.admin-test:${SMS_DLT_TEMPLATE_ID_ADMIN_TEST:}}") String dltTemplateIdAdminTest,
            @Autowired(required = false) SmsDeliveryLogRepository smsDeliveryLogRepository,
            @Autowired(required = false) EmployeeRepository employeeRepository) {

        this.enabled = enabled;
        this.provider = provider != null && !provider.isBlank() ? provider.trim() : "LOG";
        this.apiKey = apiKey != null ? apiKey.trim() : "";
        this.apiSecret = apiSecret != null ? apiSecret.trim() : "";
        this.senderId = senderId != null && !senderId.isBlank() ? senderId.trim() : "WRMS";
        this.endpointUrl = endpointUrl != null ? endpointUrl.trim() : "";
        this.dltEntityId = dltEntityId != null ? dltEntityId.trim() : "";
        this.dltTemplateIdFinal = dltTemplateIdFinal != null ? dltTemplateIdFinal.trim() : "";
        this.dltTemplateIdTentative = dltTemplateIdTentative != null ? dltTemplateIdTentative.trim() : "";
        this.dltTemplateIdAdminTest = dltTemplateIdAdminTest != null ? dltTemplateIdAdminTest.trim() : "";

        this.smsDeliveryLogRepository = smsDeliveryLogRepository;
        this.employeeRepository = employeeRepository;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(6))
                .build();

        initializeProvider();
    }

    public SmsServiceImpl(boolean enabled, String provider, String apiKey, String senderId) {
        this(enabled, provider, apiKey, "", senderId, "", "", "", "", "", null, null);
    }

    public SmsServiceImpl() {
        this(true, "LOG", "", "", "WRMS", "", "", "", "", "", null, null);
    }

    private void initializeProvider() {
        if ("FAST2SMS".equalsIgnoreCase(provider)) {
            this.activeProvider = new Fast2SmsProvider(apiKey, senderId, endpointUrl, httpClient);
        } else if ("GENERIC_REST".equalsIgnoreCase(provider)) {
            this.activeProvider = new GenericRestSmsProvider(apiKey, senderId, endpointUrl, httpClient);
        } else {
            this.activeProvider = new LogSmsProvider();
        }
    }

    @Override
    public boolean isConfigured() {
        if (!enabled) return false;
        if ("LOG".equalsIgnoreCase(provider) || "MOCK".equalsIgnoreCase(provider)) {
            return true;
        }
        return activeProvider != null && activeProvider.isConfigured();
    }

    @Override
    public boolean isRealTelecomConfigured() {
        return enabled && activeProvider != null && activeProvider.isRealTelecom() && activeProvider.isConfigured();
    }

    @Override
    public String getProviderName() {
        return activeProvider != null ? activeProvider.getProviderName() : provider;
    }

    @Override
    public SmsDeliveryResult sendSms(String toPhoneNumber, String message) {
        if (!enabled) {
            log.info("[WRMS SMS] SMS service is disabled by configuration (sms.enabled=false). Dispatch skipped.");
            return SmsDeliveryResult.skipped(provider, "SMS service is disabled by configuration", SmsDeliveryStatus.SIMULATED_LOG);
        }

        if (toPhoneNumber == null || toPhoneNumber.isBlank()) {
            log.warn("[WRMS SMS] Recipient phone number is empty. SMS dispatch skipped.");
            return SmsDeliveryResult.failure(provider, "Missing recipient phone number", SmsDeliveryStatus.SKIPPED_NO_PHONE);
        }

        String cleanPhone = PhoneUtils.normalize10Digits(toPhoneNumber);
        if (cleanPhone == null) {
            String digits = toPhoneNumber.replaceAll("[^0-9]", "");
            log.warn("[WRMS SMS] Phone number '{}' is invalid (expected 10-digit Indian mobile number).",
                    PhoneUtils.maskPhone(toPhoneNumber));
            return SmsDeliveryResult.failure(provider, "Invalid phone number length (< 10 digits)", SmsDeliveryStatus.SKIPPED_INVALID_PHONE);
        }

        // Fallback / Simulated Logging Mode when real provider credentials not present
        if (!activeProvider.isConfigured() || !activeProvider.isRealTelecom()) {
            LogSmsProvider fallback = new LogSmsProvider();
            return fallback.send(cleanPhone, message, SmsMessageType.CUSTOM, null);
        }

        return activeProvider.send(cleanPhone, message, SmsMessageType.CUSTOM, null);
    }

    @Override
    public SmsDeliveryResult sendRosterSms(RosterCycle cycle, Employee employee, boolean isFinal, String dateRange) {
        if (!enabled) {
            log.info("[WRMS SMS] SMS service disabled. Skipping roster SMS for employee {}.",
                    employee != null ? employee.getEmployeeCode() : "UNKNOWN");
            return SmsDeliveryResult.skipped(provider, "SMS service disabled", SmsDeliveryStatus.SIMULATED_LOG);
        }

        SmsMessageType msgType = isFinal ? SmsMessageType.FINAL_ROSTER : SmsMessageType.TENTATIVE_ROSTER;
        Long empId = employee != null ? employee.getId() : null;
        String empCode = employee != null ? employee.getEmployeeCode() : "UNKNOWN";
        Long cycleId = cycle != null ? cycle.getId() : null;

        // Duplicate SMS Protection
        if (smsDeliveryLogRepository != null && empId != null && cycleId != null) {
            boolean alreadySent = smsDeliveryLogRepository.existsByEmployeeIdAndRosterCycleIdAndMessageTypeAndStatusIn(
                    empId, cycleId, msgType,
                    Set.of(SmsDeliveryStatus.REQUEST_ACCEPTED, SmsDeliveryStatus.DELIVERED,
                           SmsDeliveryStatus.DELIVERY_PENDING, SmsDeliveryStatus.SIMULATED_LOG)
            );
            if (alreadySent) {
                log.info("[WRMS SMS] Duplicate protection: SMS already dispatched for employee {} (cycle #{}, type {}). Skipping.",
                        empCode, cycleId, msgType);
                recordDelivery(empId, empCode, PhoneUtils.maskPhone(employee.getContactNumber()),
                        msgType, cycleId, getProviderName(), null,
                        SmsDeliveryStatus.SKIPPED_DUPLICATE, "Duplicate SMS skipped (already dispatched)");
                return SmsDeliveryResult.skipped(getProviderName(), "Duplicate SMS skipped (already dispatched)", SmsDeliveryStatus.SKIPPED_DUPLICATE);
            }
        }

        // Validate Contact Number presence
        if (employee == null || employee.getContactNumber() == null || employee.getContactNumber().trim().isEmpty()) {
            log.warn("[WRMS SMS] Employee {} has no registered contact number. SMS dispatch skipped.", empCode);
            recordDelivery(empId, empCode, null, msgType, cycleId, getProviderName(), null,
                    SmsDeliveryStatus.SKIPPED_NO_PHONE, "Employee contact number is missing");
            return SmsDeliveryResult.skipped(getProviderName(), "Missing contact number", SmsDeliveryStatus.SKIPPED_NO_PHONE);
        }

        String rawPhone = employee.getContactNumber();
        String cleanPhone = PhoneUtils.normalize10Digits(rawPhone);
        String maskedPhone = PhoneUtils.maskPhone(rawPhone);

        // Validate Indian Mobile format
        if (cleanPhone == null) {
            log.warn("[WRMS SMS] Employee {} contact number '{}' is not a valid Indian mobile number. SMS dispatch skipped.",
                    empCode, maskedPhone);
            recordDelivery(empId, empCode, maskedPhone, msgType, cycleId, getProviderName(), null,
                    SmsDeliveryStatus.SKIPPED_INVALID_PHONE, "Invalid Indian mobile number format");
            return SmsDeliveryResult.failure(getProviderName(), "Invalid phone number format: " + maskedPhone, SmsDeliveryStatus.SKIPPED_INVALID_PHONE);
        }

        // Format exact message copy per Section 10
        String typeLabel = isFinal ? "final" : "tentative";
        String message = String.format("WRMS: Your %s roster for %s has been generated. Please check your registered email/WRMS portal.",
                typeLabel, dateRange != null ? dateRange : "");

        String dltTemplate = isFinal ? dltTemplateIdFinal : dltTemplateIdTentative;

        // Perform Dispatch
        SmsDeliveryResult result;
        if (activeProvider != null && activeProvider.isRealTelecom() && activeProvider.isConfigured()) {
            result = activeProvider.send(cleanPhone, message, msgType, dltTemplate);
        } else {
            // Simulated / Log mode
            LogSmsProvider fallback = new LogSmsProvider();
            result = fallback.send(cleanPhone, message, msgType, dltTemplate);
        }

        // Persist Audit Record
        recordDelivery(empId, empCode, maskedPhone, msgType, cycleId,
                result.provider(), result.messageId(), result.deliveryStatus(), result.errorMessage());

        return result;
    }

    @Override
    public SmsDeliveryResult sendAdminTestSms(String targetPhoneNumber, Long employeeId) {
        if (!enabled) {
            return SmsDeliveryResult.skipped(provider, "SMS service disabled", SmsDeliveryStatus.SIMULATED_LOG);
        }

        String targetPhone = targetPhoneNumber;
        String empCode = null;
        Long empId = employeeId;

        if (empId != null && employeeRepository != null) {
            Employee emp = employeeRepository.findById(empId).orElse(null);
            if (emp != null) {
                empCode = emp.getEmployeeCode();
                if (targetPhone == null || targetPhone.isBlank()) {
                    targetPhone = emp.getContactNumber();
                }
            }
        }

        if (targetPhone == null || targetPhone.isBlank()) {
            return SmsDeliveryResult.failure(getProviderName(), "Target phone number is required", SmsDeliveryStatus.SKIPPED_NO_PHONE);
        }

        String cleanPhone = PhoneUtils.normalize10Digits(targetPhone);
        String maskedPhone = PhoneUtils.maskPhone(targetPhone);

        if (cleanPhone == null) {
            return SmsDeliveryResult.failure(getProviderName(), "Invalid Indian mobile number: " + maskedPhone, SmsDeliveryStatus.SKIPPED_INVALID_PHONE);
        }

        // Exact test message per Section 16
        String testMessage = "WRMS test SMS: SMS notification service is working.";

        SmsDeliveryResult result;
        if (activeProvider != null && activeProvider.isRealTelecom() && activeProvider.isConfigured()) {
            result = activeProvider.send(cleanPhone, testMessage, SmsMessageType.ADMIN_TEST, dltTemplateIdAdminTest);
        } else {
            LogSmsProvider fallback = new LogSmsProvider();
            result = fallback.send(cleanPhone, testMessage, SmsMessageType.ADMIN_TEST, dltTemplateIdAdminTest);
        }

        recordDelivery(empId, empCode, maskedPhone, SmsMessageType.ADMIN_TEST, null,
                result.provider(), result.messageId(), result.deliveryStatus(), result.errorMessage());

        return result;
    }

    @Override
    public SmsDiagnosticsResponse getDiagnostics() {
        int totalActive = 0;
        int validMobile = 0;
        int missingMobile = 0;

        if (employeeRepository != null) {
            List<Employee> activeEmployees = employeeRepository.findByActiveTrueOrderByIdAsc();
            totalActive = activeEmployees.size();
            for (Employee e : activeEmployees) {
                if (e.getContactNumber() != null && PhoneUtils.isValidIndianMobile(e.getContactNumber())) {
                    validMobile++;
                } else {
                    missingMobile++;
                }
            }
        }

        boolean realReady = isRealTelecomConfigured();
        boolean dltReady = !dltEntityId.isBlank();
        String maskedDlt = dltEntityId.length() >= 4
                ? "******" + dltEntityId.substring(dltEntityId.length() - 4)
                : (dltEntityId.isBlank() ? null : "****");

        String operatingMode = realReady ? "LIVE_TELECOM" : "SIMULATED_LOG";
        String notice = realReady
                ? "Real telecom SMS provider is configured and ready for live dispatch."
                : "Real telecom SMS provider credentials not configured. Operating in simulated log mode.";

        return new SmsDiagnosticsResponse(
                enabled,
                getProviderName(),
                isConfigured(),
                realReady,
                !apiKey.isBlank(),
                !apiSecret.isBlank(),
                senderId,
                !endpointUrl.isBlank(),
                dltReady,
                maskedDlt,
                totalActive,
                validMobile,
                missingMobile,
                operatingMode,
                notice
        );
    }

    @Override
    public List<SmsDeliveryLog> getRecentLogs() {
        if (smsDeliveryLogRepository != null) {
            return smsDeliveryLogRepository.findTop50ByOrderByCreatedAtDesc();
        }
        return List.of();
    }

    private void recordDelivery(Long empId, String empCode, String mobileMasked,
                                SmsMessageType type, Long cycleId, String prov,
                                String messageId, SmsDeliveryStatus status, String failureReason) {
        if (smsDeliveryLogRepository == null) return;
        try {
            SmsDeliveryLog logEntry = new SmsDeliveryLog(
                    empId, empCode, mobileMasked, type, cycleId,
                    prov != null ? prov : getProviderName(),
                    messageId, status, failureReason
            );
            smsDeliveryLogRepository.save(logEntry);
        } catch (Exception ex) {
            log.warn("[WRMS SMS] Failed to record delivery audit log: {}", ex.getMessage());
        }
    }

    public static String maskPhone(String phone) {
        return PhoneUtils.maskPhone(phone);
    }
}
