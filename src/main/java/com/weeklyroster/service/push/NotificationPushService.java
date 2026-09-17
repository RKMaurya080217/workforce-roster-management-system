package com.weeklyroster.service.push;

import com.weeklyroster.entity.Employee;
import com.weeklyroster.entity.RosterCycle;
import com.weeklyroster.entity.User;

import java.util.Map;

public interface NotificationPushService {

    /**
     * Registers or updates a device token for an authenticated user/employee.
     */
    boolean registerToken(User user, Employee employee, String token, String deviceType);

    /**
     * Deactivates a device token for an authenticated user.
     */
    boolean deactivateToken(String token, User user);

    /**
     * Sends a roster notification to an employee's registered active devices.
     * Guaranteed exact format:
     * - Final: "Your final roster for {START_DATE} – {END_DATE} has been sent to your registered email."
     * - Tentative: "Your tentative roster for {START_DATE} – {END_DATE} has been sent to your registered email."
     *
     * @return number of devices successfully delivered
     */
    int sendRosterNotification(RosterCycle cycle, Employee employee, boolean isFinal, String dateRange);

    /**
     * Sends a generic push notification to all active devices of a user.
     */
    boolean sendNotificationToUser(User user, String title, String body, Map<String, String> data);

    /**
     * Sends a test notification to the authenticated Admin's registered devices.
     * Content: "WRMS test notification — your mobile push notification is working."
     */
    boolean sendAdminTestNotification(User adminUser);

    /**
     * Sends a test notification to a specific employee's registered devices (Admin only).
     * Content: "WRMS test notification — your mobile push notification is working."
     */
    boolean sendAdminTestNotification(User adminUser, Long targetEmployeeId);

    /**
     * Returns true if server credentials are fully configured for live FCM HTTP v1 dispatch.
     */
    boolean isConfigured();

    /**
     * Returns count of active registered devices for a user.
     */
    long getActiveTokenCountForUser(User user);

    /**
     * Returns comprehensive push diagnostics for the given user.
     */
    Map<String, Object> getPushDiagnostics(User user);
}
