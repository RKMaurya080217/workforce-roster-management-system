package com.weeklyroster.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class Batch44LoginPageRoleSelectionTest {

    @Test
    @DisplayName("Test 1: index.html contains accessible role selection with Admin default and no exposed credentials")
    void testIndexHtmlRoleSelectionAndNoExposedCredentials() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Role selector exists and uses radio semantics
        assertTrue(html.contains("role=\"radiogroup\""), "Must contain accessible role radiogroup");
        assertTrue(html.contains("name=\"authRole\""), "Must contain authRole radio inputs");
        assertTrue(html.contains("value=\"ADMIN\""), "Must contain ADMIN radio option");
        assertTrue(html.contains("value=\"EMPLOYEE\""), "Must contain EMPLOYEE radio option");

        // 2. Admin is checked by default
        assertTrue(html.contains("id=\"roleAdmin\" name=\"authRole\" value=\"ADMIN\" checked"), "Admin role must be default checked");

        // 3. Role cards have title and description
        assertTrue(html.contains("Administrative access"), "Admin card must have description");
        assertTrue(html.contains("Employee roster access"), "Employee card must have description");

        // 4. Role-specific username label
        assertTrue(html.contains("id=\"loginUsernameLabel\">Admin Username</label>"), "Default label must be Admin Username");

        // 5. Password field has eye toggle with accessible attributes
        assertTrue(html.contains("id=\"togglePasswordBtn\""), "Password toggle button must exist");
        assertTrue(html.contains("aria-label=\"Show password\"") || html.contains("title=\"Show password\""), "Must have accessible toggle labels");

        // 6. Safe development notice exists without passwords
        assertTrue(html.contains("DEVELOPMENT ENVIRONMENT") || html.contains("DEMO ACCESS"), "Safe development notice must exist");
        assertTrue(html.contains("safe-demo-notice"), "Safe demo notice container must exist");

        // 7. NO passwords exposed anywhere in HTML
        assertFalse(html.contains("Admin@123"), "HTML must NOT contain Admin@123 password");
        assertFalse(html.contains("password123"), "HTML must NOT contain password123 password");
        assertFalse(html.contains("admin / Admin@123"), "HTML must NOT contain exposed admin credentials");
        assertFalse(html.contains("emp001 / password123"), "HTML must NOT contain exposed staff credentials");
        assertFalse(html.contains("Development Fast-Login:"), "HTML must NOT contain Development Fast-Login block");
    }

    @Test
    @DisplayName("Test 2: styles.css contains role card layout, selected state, focus outline, and responsive styles")
    void testStylesCssRoleSelectionClasses() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(css.contains(".role-cards-grid"), "CSS must contain .role-cards-grid");
        assertTrue(css.contains(".role-card"), "CSS must contain .role-card");
        assertTrue(css.contains(".role-card.selected"), "CSS must contain .role-card.selected");
        assertTrue(css.contains(".role-card:focus-within"), "CSS must contain .role-card:focus-within for keyboard accessibility");
        assertTrue(css.contains(".role-card-radio-indicator"), "CSS must contain radio indicator");
        assertTrue(css.contains(".safe-demo-notice"), "CSS must contain .safe-demo-notice");
        assertTrue(css.contains("@media (max-width: 480px)"), "CSS must contain mobile responsive rules");
    }

    @Test
    @DisplayName("Test 3: app.js contains role switching, validation, generic error UX, and zero fast-fill credentials")
    void testAppJsRoleSwitchingAndSecurity() throws Exception {
        InputStream is = new ClassPathResource("static/app.js").getInputStream();
        String js = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Role switching logic
        assertTrue(js.contains("setLoginRole"), "Must contain setLoginRole helper");
        assertTrue(js.contains("Admin Username"), "Must update label to Admin Username");
        assertTrue(js.contains("Employee Code"), "Must update label to Employee Code");
        assertTrue(js.contains("Sign In as Admin"), "Must update submit button to Sign In as Admin");
        assertTrue(js.contains("Sign In as Employee"), "Must update submit button to Sign In as Employee");

        // 2. Role validation in handleLogin
        assertTrue(js.contains("ROLE_ADMIN"), "Must validate ROLE_ADMIN");
        assertTrue(js.contains("ROLE_EMPLOYEE"), "Must validate ROLE_EMPLOYEE");
        assertTrue(js.contains("Invalid Admin credentials."), "Must show generic Admin error");
        assertTrue(js.contains("Invalid employee credentials."), "Must show generic Employee error");

        // 3. Zero passwords in JS
        assertFalse(js.contains("Admin@123"), "app.js must NOT contain Admin@123");
        assertFalse(js.contains("password123"), "app.js must NOT contain password123");
        assertFalse(js.contains("demoAdminBtn"), "app.js must NOT contain demoAdminBtn");
        assertFalse(js.contains("demoEmpBtn"), "app.js must NOT contain demoEmpBtn");
    }
}
