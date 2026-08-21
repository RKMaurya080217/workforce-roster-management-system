package com.weeklyroster.service;

import com.weeklyroster.dto.response.AuditLogResponse;
import com.weeklyroster.entity.AuditAction;
import com.weeklyroster.entity.AuditLog;
import com.weeklyroster.repository.AuditLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditService auditService;

    @BeforeEach
    void setUp() {
        SecurityContext securityContext = mock(SecurityContext.class);
        lenient().when(securityContext.getAuthentication()).thenReturn(
                new UsernamePasswordAuthenticationToken("admin", "Admin@123", List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
        SecurityContextHolder.setContext(securityContext);
    }

    @Test
    @DisplayName("AuditService records action with current user actor and persists correctly")
    void testLogAction() {
        AuditLog savedLog = new AuditLog();
        savedLog.setId(1L);
        savedLog.setAction(AuditAction.ROSTER_PUBLISHED);
        savedLog.setActor("admin");
        savedLog.setEntityType("ROSTER_CYCLE");
        savedLog.setEntityId(10L);
        savedLog.setCycleId(10L);
        savedLog.setTimestamp(LocalDateTime.now());
        savedLog.setSource("MANUAL");

        when(auditLogRepository.save(any(AuditLog.class))).thenReturn(savedLog);

        AuditLog result = auditService.log(
                AuditAction.ROSTER_PUBLISHED,
                "ROSTER_CYCLE",
                10L,
                10L,
                null,
                null,
                "GENERATED",
                "PUBLISHED",
                "Admin published roster",
                "MANUAL"
        );

        assertNotNull(result);
        assertEquals(AuditAction.ROSTER_PUBLISHED, result.getAction());
        assertEquals("admin", result.getActor());
        verify(auditLogRepository).save(any(AuditLog.class));
    }

    @Test
    @DisplayName("AuditService searches logs matching specified filters")
    void testSearchAuditLogs() {
        AuditLog log = new AuditLog();
        log.setId(1L);
        log.setAction(AuditAction.SHIFT_OVERRIDDEN);
        log.setActor("admin");
        log.setEmployeeName("Alice");
        log.setTimestamp(LocalDateTime.now());

        when(auditLogRepository.searchAuditLogs(eq(1L), eq(AuditAction.SHIFT_OVERRIDDEN), any(), any(), any(), any()))
                .thenReturn(List.of(log));

        List<AuditLogResponse> logs = auditService.searchAuditLogs(1L, AuditAction.SHIFT_OVERRIDDEN, null, null, null, null);

        assertNotNull(logs);
        assertEquals(1, logs.size());
        assertEquals(AuditAction.SHIFT_OVERRIDDEN, logs.get(0).action());
    }
}
