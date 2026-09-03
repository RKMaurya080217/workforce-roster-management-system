package com.weeklyroster.service.email;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class BrevoEmailServiceTest {

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> httpResponse;

    private ObjectMapper objectMapper = new ObjectMapper();
    private BrevoEmailService brevoEmailService;

    @BeforeEach
    void setUp() {
        brevoEmailService = new BrevoEmailService(httpClient, objectMapper);
        ReflectionTestUtils.setField(brevoEmailService, "apiKey", "xkeysib-test-fake-key-12345");
        ReflectionTestUtils.setField(brevoEmailService, "rawApiUrl", "https://api.brevo.com/v3/smtp/email");
        ReflectionTestUtils.setField(brevoEmailService, "defaultSenderEmail", "rajatkumarmaury@gmail.com");
        ReflectionTestUtils.setField(brevoEmailService, "defaultSenderName", "WRMS");
    }

    @Test
    @DisplayName("Test 1: Successful Brevo HTTPS Email Dispatch (HTTP 201 Created)")
    void testSuccessfulBrevoEmailSend() throws Exception {
        when(httpResponse.statusCode()).thenReturn(201);
        when(httpResponse.body()).thenReturn("{\"messageId\":\"<202609030500.123456@smtp-relay.brevo.com>\"}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        EmailMessage message = EmailMessage.builder()
                .to("rkmaurya080217@gmail.com", "Rajat Maurya")
                .from("rajatkumarmaury@gmail.com", "WRMS")
                .subject("WRMS Weekly Roster — 07 Sept 2026 to 13 Sept 2026")
                .textBody("Duty schedule details")
                .htmlBody("<html><body><h1>Roster</h1></body></html>")
                .addAttachment(new EmailAttachment("Roster.xlsx", "fake-xlsx-content".getBytes(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .build();

        EmailDeliveryResult result = brevoEmailService.sendEmail(message);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("BREVO", result.getProvider());
        assertEquals("<202609030500.123456@smtp-relay.brevo.com>", result.getMessageId());
        assertNull(result.getErrorMessage());
        assertEquals(200, result.getHttpStatus());
    }

    @Test
    @DisplayName("Test 2: Brevo Missing API Key fails fast with EMAIL_NOT_CONFIGURED")
    void testMissingApiKeyFailsFast() {
        ReflectionTestUtils.setField(brevoEmailService, "apiKey", "");

        EmailMessage message = EmailMessage.builder()
                .to("rkmaurya080217@gmail.com")
                .subject("Test")
                .textBody("Test")
                .build();

        EmailDeliveryResult result = brevoEmailService.sendEmail(message);

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("BREVO", result.getProvider());
        assertTrue(result.getErrorMessage().contains("EMAIL_NOT_CONFIGURED"));
        assertEquals(401, result.getHttpStatus());
        verifyNoInteractions(httpClient);
    }

    @Test
    @DisplayName("Test 3: Brevo 401 Unauthorized returns meaningful error without retrying")
    void testBrevo401Unauthorized() throws Exception {
        when(httpResponse.statusCode()).thenReturn(401);
        when(httpResponse.body()).thenReturn("{\"code\":\"unauthorized\",\"message\":\"Key not found\"}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        EmailMessage message = EmailMessage.builder()
                .to("rkmaurya080217@gmail.com")
                .subject("Test")
                .textBody("Test")
                .build();

        EmailDeliveryResult result = brevoEmailService.sendEmail(message);

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("BREVO", result.getProvider());
        assertTrue(result.getErrorMessage().contains("401") || result.getErrorMessage().contains("unauthorized"));
        assertEquals(401, result.getHttpStatus());
        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("Test 4: Brevo 429 Rate Limit exceeded handling")
    void testBrevo429RateLimit() throws Exception {
        when(httpResponse.statusCode()).thenReturn(429);
        when(httpResponse.body()).thenReturn("{\"code\":\"too_many_requests\",\"message\":\"Rate limit exceeded\"}");
        doReturn(httpResponse).when(httpClient).send(any(HttpRequest.class), any());

        EmailMessage message = EmailMessage.builder()
                .to("rkmaurya080217@gmail.com")
                .subject("Test")
                .textBody("Test")
                .build();

        EmailDeliveryResult result = brevoEmailService.sendEmail(message);

        assertNotNull(result);
        assertFalse(result.isSuccess());
        assertEquals("BREVO", result.getProvider());
        assertTrue(result.getErrorMessage().contains("429") || result.getErrorMessage().contains("Rate Limit"));
        assertEquals(429, result.getHttpStatus());
        verify(httpClient, times(1)).send(any(HttpRequest.class), any());
    }

    @Test
    @DisplayName("Test 5: EmailService Provider Selection (BREVO by default, SMTP on switch)")
    void testEmailServiceProviderSelection() {
        SmtpEmailService mockSmtp = mock(SmtpEmailService.class);
        EmailService service = new EmailService(brevoEmailService, mockSmtp);

        // 1. Default -> BREVO
        ReflectionTestUtils.setField(service, "configuredProvider", "BREVO");
        assertEquals("BREVO", service.getActiveProviderName());
        assertTrue(service.isConfigured());

        // 2. Switch to SMTP
        ReflectionTestUtils.setField(service, "configuredProvider", "SMTP");
        when(mockSmtp.getProviderName()).thenReturn("SMTP");
        assertEquals("SMTP", service.getActiveProviderName());

        // 3. Status map check
        Map<String, Object> status = service.getProviderStatus();
        assertTrue((Boolean) status.get("brevoAvailable"));
        assertTrue((Boolean) status.get("smtpFallbackAvailable"));
        assertTrue((Boolean) status.get("smtpFallbackEnabled"));
    }

    @Test
    @DisplayName("Test 6: Brevo URL resolution handles base URL, trailing slashes, and full endpoints without double path")
    void testBrevoUrlResolution() {
        // 1. Base URL
        ReflectionTestUtils.setField(brevoEmailService, "rawApiUrl", "https://api.brevo.com");
        assertEquals("https://api.brevo.com/v3/smtp/email", brevoEmailService.getResolvedEndpointUrl());

        // 2. Base URL with trailing slash
        ReflectionTestUtils.setField(brevoEmailService, "rawApiUrl", "https://api.brevo.com/");
        assertEquals("https://api.brevo.com/v3/smtp/email", brevoEmailService.getResolvedEndpointUrl());

        // 3. Full endpoint
        ReflectionTestUtils.setField(brevoEmailService, "rawApiUrl", "https://api.brevo.com/v3/smtp/email");
        assertEquals("https://api.brevo.com/v3/smtp/email", brevoEmailService.getResolvedEndpointUrl());

        // 4. Null / blank fallback
        ReflectionTestUtils.setField(brevoEmailService, "rawApiUrl", "");
        assertEquals("https://api.brevo.com/v3/smtp/email", brevoEmailService.getResolvedEndpointUrl());
    }
}
