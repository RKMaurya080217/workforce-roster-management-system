package com.weeklyroster.service;

import com.weeklyroster.dto.request.CreateProfileChangeRequest;
import com.weeklyroster.dto.request.ProfileChangeDecisionRequest;
import com.weeklyroster.dto.response.ProfileChangeRequestResponse;
import com.weeklyroster.entity.*;
import com.weeklyroster.exception.BusinessException;
import com.weeklyroster.exception.ResourceNotFoundException;
import com.weeklyroster.repository.EmployeeRepository;
import com.weeklyroster.repository.ProfileChangeRequestRepository;
import com.weeklyroster.repository.UserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ProfileChangeRequestServiceTest {

    @Mock
    private ProfileChangeRequestRepository profileChangeRequestRepository;

    @Mock
    private EmployeeRepository employeeRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private EmployeeActivityLogService activityLogService;

    private ProfileChangeRequestService service;

    private User employeeUser;
    private User adminUser;
    private Employee employee1;
    private Employee employee2;

    @BeforeEach
    void setUp() {
        service = new ProfileChangeRequestService(
                profileChangeRequestRepository,
                employeeRepository,
                userRepository,
                auditService,
                notificationService,
                activityLogService
        );

        employeeUser = new User();
        employeeUser.setId(10L);
        employeeUser.setUsername("rajat");
        employeeUser.setRole(Role.ROLE_EMPLOYEE);

        adminUser = new User();
        adminUser.setId(1L);
        adminUser.setUsername("admin");
        adminUser.setRole(Role.ROLE_ADMIN);

        employee1 = new Employee();
        employee1.setId(1L);
        employee1.setEmployeeCode("EMP001");
        employee1.setFirstName("Rajat");
        employee1.setLastName("Maurya");
        employee1.setEmail("rajat@weeklyroster.com");
        employee1.setGender(Gender.MALE);
        employee1.setActive(true);
        employee1.setUser(employeeUser);

        employee2 = new Employee();
        employee2.setId(2L);
        employee2.setEmployeeCode("EMP002");
        employee2.setFirstName("Prachi");
        employee2.setLastName("Mishra");
        employee2.setEmail("prachi@weeklyroster.com");
        employee2.setGender(Gender.FEMALE);
        employee2.setActive(true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAsEmployee() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("rajat", "password",
                        List.of(new SimpleGrantedAuthority("ROLE_EMPLOYEE")))
        );
        when(userRepository.findByUsername("rajat")).thenReturn(Optional.of(employeeUser));
        when(employeeRepository.findByUserUsername("rajat")).thenReturn(Optional.of(employee1));
    }

    private void authenticateAsAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "password",
                        List.of(new SimpleGrantedAuthority("ROLE_ADMIN")))
        );
    }

    @Test
    @DisplayName("1. Employee can create own profile change request")
    void test1_EmployeeCanCreateOwnProfileChangeRequest() {
        authenticateAsEmployee();

        when(profileChangeRequestRepository.existsByEmployeeIdAndFieldNameAndStatus(1L, "firstName", ProfileChangeStatus.PENDING))
                .thenReturn(false);
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class))).thenAnswer(i -> {
            ProfileChangeRequest r = i.getArgument(0);
            r.setId(100L);
            return r;
        });

        CreateProfileChangeRequest request = new CreateProfileChangeRequest("firstName", "Rajendra");
        ProfileChangeRequestResponse response = service.submitRequest(request);

        assertNotNull(response);
        assertEquals(100L, response.id());
        assertEquals(1L, response.employeeId());
        assertEquals("firstName", response.fieldName());
        assertEquals("Rajat", response.currentValue());
        assertEquals("Rajendra", response.requestedValue());
        assertEquals(ProfileChangeStatus.PENDING, response.status());

        // Verify activity log, notification, and audit
        verify(activityLogService).logActivity(eq(1L), eq("rajat"), eq(ActivityCategory.PROFILE),
                eq("PROFILE_CHANGE_REQUESTED"), eq(ActivityStatus.SUCCESS), anyString(), eq("WEB"));
        verify(notificationService).createNotification(eq("rajat"), eq(1L), anyString(), anyString(),
                eq(NotificationType.PROFILE_CHANGE_REQUESTED), eq("profile"), eq(100L));
        verify(auditService).log(eq(AuditAction.PROFILE_CHANGE_REQUESTED), eq("PROFILE_CHANGE_REQUEST"),
                eq(100L), isNull(), eq(1L), eq("Rajat Maurya"), eq("Rajat"), eq("Rajendra"), anyString(), eq("WEB"));
    }

    @Test
    @DisplayName("2. Employee identity is strictly extracted from SecurityContext")
    void test2_EmployeeIdentityFromSecurityContext() {
        // Without authentication, submitting throws AccessDeniedException
        CreateProfileChangeRequest request = new CreateProfileChangeRequest("lastName", "Verma");
        assertThrows(AccessDeniedException.class, () -> service.submitRequest(request));
    }

    @Test
    @DisplayName("3. Employee can view only own requests")
    void test3_EmployeeCanViewOnlyOwnRequests() {
        authenticateAsEmployee();

        ProfileChangeRequest pcr1 = new ProfileChangeRequest();
        pcr1.setId(101L);
        pcr1.setEmployee(employee1);
        pcr1.setFieldName("firstName");
        pcr1.setCurrentValue("Rajat");
        pcr1.setRequestedValue("Rajendra");
        pcr1.setStatus(ProfileChangeStatus.PENDING);
        pcr1.setRequestedAt(LocalDateTime.now());

        when(profileChangeRequestRepository.findByEmployeeIdOrderByRequestedAtDesc(1L)).thenReturn(List.of(pcr1));

        List<ProfileChangeRequestResponse> myRequests = service.getMyRequests();
        assertEquals(1, myRequests.size());
        assertEquals(101L, myRequests.get(0).id());

        // Accessing other employee's request throws AccessDeniedException
        ProfileChangeRequest pcrOther = new ProfileChangeRequest();
        pcrOther.setId(102L);
        pcrOther.setEmployee(employee2);
        when(profileChangeRequestRepository.findById(102L)).thenReturn(Optional.of(pcrOther));

        assertThrows(AccessDeniedException.class, () -> service.getById(102L));
    }

    @Test
    @DisplayName("4. Admin can view pending requests")
    void test4_AdminCanViewPendingRequests() {
        authenticateAsAdmin();

        ProfileChangeRequest pcr1 = new ProfileChangeRequest();
        pcr1.setId(101L);
        pcr1.setEmployee(employee1);
        pcr1.setFieldName("email");
        pcr1.setCurrentValue("rajat@weeklyroster.com");
        pcr1.setRequestedValue("rajat.new@weeklyroster.com");
        pcr1.setStatus(ProfileChangeStatus.PENDING);
        pcr1.setRequestedAt(LocalDateTime.now());

        when(profileChangeRequestRepository.findByStatusOrderByRequestedAtAsc(ProfileChangeStatus.PENDING))
                .thenReturn(List.of(pcr1));

        List<ProfileChangeRequestResponse> pending = service.getPendingRequests();
        assertEquals(1, pending.size());
        assertEquals("email", pending.get(0).fieldName());
    }

    @Test
    @DisplayName("5 & 6. Admin can approve request and employee profile is updated")
    void test5_AdminApproveUpdatesEmployee() {
        authenticateAsAdmin();

        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setId(101L);
        pcr.setEmployee(employee1);
        pcr.setFieldName("firstName");
        pcr.setCurrentValue("Rajat");
        pcr.setRequestedValue("Rajendra");
        pcr.setStatus(ProfileChangeStatus.PENDING);
        pcr.setRequestedAt(LocalDateTime.now());

        when(profileChangeRequestRepository.findById(101L)).thenReturn(Optional.of(pcr));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class))).thenAnswer(i -> i.getArgument(0));

        ProfileChangeRequestResponse response = service.approve(101L, new ProfileChangeDecisionRequest("Approved as requested"));

        assertEquals(ProfileChangeStatus.APPROVED, response.status());
        assertEquals("Approved as requested", response.adminRemarks());
        assertNotNull(response.reviewedAt());

        // Verify employee was updated
        assertEquals("Rajendra", employee1.getFirstName());
        verify(employeeRepository).save(employee1);

        // Verify activity log, audit, notification
        verify(activityLogService).logActivity(eq(1L), eq("rajat"), eq(ActivityCategory.PROFILE),
                eq("PROFILE_CHANGE_APPROVED"), eq(ActivityStatus.SUCCESS), contains("Approved as requested"), eq("ADMIN"));
        verify(auditService).log(eq(AuditAction.PROFILE_CHANGE_APPROVED), eq("EMPLOYEE"),
                eq(1L), isNull(), eq(1L), anyString(), eq("Rajat"), eq("Rajendra"), eq("Approved as requested"), eq("MANUAL"));
        verify(notificationService).createNotification(eq("rajat"), eq(1L), eq("Profile Change Request Approved"),
                contains("Approved as requested"), eq(NotificationType.PROFILE_CHANGE_DECISION), eq("profile"), eq(101L));
    }

    @Test
    @DisplayName("7. Admin approval of gender change updates employee gender")
    void test7_AdminApproveGenderChange() {
        authenticateAsAdmin();

        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setId(102L);
        pcr.setEmployee(employee1);
        pcr.setFieldName("gender");
        pcr.setCurrentValue("MALE");
        pcr.setRequestedValue("FEMALE");
        pcr.setStatus(ProfileChangeStatus.PENDING);
        pcr.setRequestedAt(LocalDateTime.now());

        when(profileChangeRequestRepository.findById(102L)).thenReturn(Optional.of(pcr));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class))).thenAnswer(i -> i.getArgument(0));

        service.approve(102L, null);

        assertEquals(Gender.FEMALE, employee1.getGender());
        verify(employeeRepository).save(employee1);
    }

    @Test
    @DisplayName("10 & 11. Admin can reject request without modifying employee")
    void test10_AdminRejectDoesNotModifyEmployee() {
        authenticateAsAdmin();

        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setId(103L);
        pcr.setEmployee(employee1);
        pcr.setFieldName("firstName");
        pcr.setCurrentValue("Rajat");
        pcr.setRequestedValue("InvalidName");
        pcr.setStatus(ProfileChangeStatus.PENDING);
        pcr.setRequestedAt(LocalDateTime.now());

        when(profileChangeRequestRepository.findById(103L)).thenReturn(Optional.of(pcr));
        when(profileChangeRequestRepository.save(any(ProfileChangeRequest.class))).thenAnswer(i -> i.getArgument(0));

        ProfileChangeRequestResponse response = service.reject(103L, new ProfileChangeDecisionRequest("Does not match HR records"));

        assertEquals(ProfileChangeStatus.REJECTED, response.status());
        assertEquals("Does not match HR records", response.adminRemarks());
        assertNotNull(response.reviewedAt());

        // Verify employee was NOT modified
        assertEquals("Rajat", employee1.getFirstName());
        verify(employeeRepository, never()).save(any(Employee.class));

        // Verify activity log, audit, notification
        verify(activityLogService).logActivity(eq(1L), eq("rajat"), eq(ActivityCategory.PROFILE),
                eq("PROFILE_CHANGE_REJECTED"), eq(ActivityStatus.SUCCESS), contains("Does not match HR records"), eq("ADMIN"));
        verify(auditService).log(eq(AuditAction.PROFILE_CHANGE_REJECTED), eq("PROFILE_CHANGE_REQUEST"),
                eq(103L), isNull(), eq(1L), anyString(), eq("Rajat"), eq("InvalidName"), eq("Does not match HR records"), eq("MANUAL"));
        verify(notificationService).createNotification(eq("rajat"), eq(1L), eq("Profile Change Request Rejected"),
                contains("Does not match HR records"), eq(NotificationType.PROFILE_CHANGE_DECISION), eq("profile"), eq(103L));
    }

    @Test
    @DisplayName("13 & 18. Non-whitelisted and sensitive fields are rejected")
    void test13_NonWhitelistedFieldsRejected() {
        authenticateAsEmployee();

        // Sensitive / unauthorized fields
        List<String> invalidFields = List.of("password", "role", "salary", "active", "id", "user", "isAdmin");
        for (String field : invalidFields) {
            CreateProfileChangeRequest req = new CreateProfileChangeRequest(field, "someValue");
            assertThrows(BusinessException.class, () -> service.submitRequest(req),
                    "Field " + field + " must be rejected");
        }
    }

    @Test
    @DisplayName("14. Duplicate pending request is prevented")
    void test14_DuplicatePendingRequestPrevented() {
        authenticateAsEmployee();

        when(profileChangeRequestRepository.existsByEmployeeIdAndFieldNameAndStatus(1L, "firstName", ProfileChangeStatus.PENDING))
                .thenReturn(true);

        CreateProfileChangeRequest request = new CreateProfileChangeRequest("firstName", "Rajendra");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.submitRequest(request));
        assertTrue(ex.getMessage().contains("pending change request already exists"));
    }

    @Test
    @DisplayName("15. Already approved request cannot be approved again")
    void test15_AlreadyApprovedRequestCannotBeApprovedAgain() {
        authenticateAsAdmin();

        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setId(104L);
        pcr.setEmployee(employee1);
        pcr.setFieldName("firstName");
        pcr.setStatus(ProfileChangeStatus.APPROVED);

        when(profileChangeRequestRepository.findById(104L)).thenReturn(Optional.of(pcr));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.approve(104L, null));
        assertTrue(ex.getMessage().contains("not in PENDING state"));
    }

    @Test
    @DisplayName("16. Already rejected request cannot be approved or rejected again")
    void test16_AlreadyRejectedRequestCannotBeProcessedAgain() {
        authenticateAsAdmin();

        ProfileChangeRequest pcr = new ProfileChangeRequest();
        pcr.setId(105L);
        pcr.setEmployee(employee1);
        pcr.setFieldName("firstName");
        pcr.setStatus(ProfileChangeStatus.REJECTED);

        when(profileChangeRequestRepository.findById(105L)).thenReturn(Optional.of(pcr));

        assertThrows(BusinessException.class, () -> service.approve(105L, null));
        assertThrows(BusinessException.class, () -> service.reject(105L, null));
    }

    @Test
    @DisplayName("17. Invalid field values (e.g. invalid email or gender) are rejected on submission")
    void test17_InvalidValuesRejected() {
        authenticateAsEmployee();

        // Invalid email
        CreateProfileChangeRequest invalidEmail = new CreateProfileChangeRequest("email", "not-an-email");
        assertThrows(BusinessException.class, () -> service.submitRequest(invalidEmail));

        // Invalid gender
        CreateProfileChangeRequest invalidGender = new CreateProfileChangeRequest("gender", "UNKNOWN_GENDER");
        assertThrows(BusinessException.class, () -> service.submitRequest(invalidGender));

        // Identical value
        CreateProfileChangeRequest sameName = new CreateProfileChangeRequest("firstName", "Rajat");
        assertThrows(BusinessException.class, () -> service.submitRequest(sameName));
    }
}
