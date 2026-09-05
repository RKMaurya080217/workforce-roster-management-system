package com.weeklyroster.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class Batch45ProductionLoginAndSharedLayoutTest {

    @Test
    @DisplayName("Test 1: index.html contains premium hero panel with all 4 feature cards, mini roster preview, and status indicator")
    void testIndexHtmlPremiumHeroPanel() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Enterprise badge & headline
        assertTrue(html.contains("Enterprise Operations v2.0"), "Must contain enterprise badge");
        assertTrue(html.contains("Intelligent Workforce"), "Must contain Intelligent Workforce headline");
        assertTrue(html.contains("Roster Management"), "Must contain Roster Management headline");

        // 2. All 4 feature cards
        assertTrue(html.contains("Fair Rotation"), "Must contain Fair Rotation card");
        assertTrue(html.contains("Balanced workforce assignments with intelligent rotation."), "Must contain Fair Rotation desc");
        assertTrue(html.contains("Smart Scheduling"), "Must contain Smart Scheduling card");
        assertTrue(html.contains("Constraint-aware automated roster generation."), "Must contain Smart Scheduling desc");
        assertTrue(html.contains("Staff Protection"), "Must contain Staff Protection card");
        assertTrue(html.contains("Automated enforcement of rest, gender and shift safety rules."), "Must contain Staff Protection desc");
        assertTrue(html.contains("Leave Synchronization"), "Must contain Leave Synchronization card");
        assertTrue(html.contains("Real-time roster adjustment based on approved leave."), "Must contain Leave Synchronization desc");

        // 3. Mini roster visualization without real employee data
        assertTrue(html.contains("hero-mini-roster"), "Must contain mini roster container");
        assertTrue(html.contains("Workforce Overview &bull; Capacity Balance"), "Must contain workforce preview title");
        assertTrue(html.contains("pill-morning"), "Must contain morning shift capacity pills");
        assertTrue(html.contains("pill-night"), "Must contain night shift capacity pills");

        // 4. Hero status indicator
        assertTrue(html.contains("hero-status-indicator"), "Must contain hero status indicator");
        assertTrue(html.contains("Scheduling Engine Active"), "Must contain active engine status");
    }

    @Test
    @DisplayName("Test 2: index.html and app-footer contain required branding, contact info, clickable links, version, and copyright")
    void testSharedFootersAndContactInfo() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Login Footer
        assertTrue(html.contains("auth-footer"), "Must contain auth-footer on login view");
        assertTrue(html.contains("Rajat Kumar Maurya"), "Auth footer must mention Rajat Kumar Maurya");
        assertTrue(html.contains("href=\"tel:8565005534\""), "Auth footer must have clickable phone link");
        assertTrue(html.contains("href=\"mailto:rajatkumarmaury@gmail.com\""), "Auth footer must have clickable email link");
        assertTrue(html.contains("2026 WRMS. All Rights Reserved."), "Auth footer must have copyright notice");

        // 2. Shared App Footer
        assertTrue(html.contains("class=\"app-footer\""), "Must contain shared app-footer in main app layout");
        assertTrue(html.contains("app-footer-container"), "Must contain app-footer container");
        assertTrue(html.contains("Workforce Roster Management System"), "App footer must display full product name");
        assertTrue(html.contains("footer-contact-item"), "Must contain contact items in app-footer");
        assertTrue(html.contains("Release Version:"), "Must display version meta");
        assertTrue(html.contains("v2.0"), "Must display v2.0 version badge");
    }

    @Test
    @DisplayName("Test 3: styles.css contains premium hero gradients, glow, mini-roster pills, and shared footer grid")
    void testStylesCssHeroAndFooterLayout() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(css.contains(".auth-hero"), "CSS must contain .auth-hero");
        assertTrue(css.contains(".hero-bg-glow"), "CSS must contain .hero-bg-glow");
        assertTrue(css.contains(".hero-mini-roster"), "CSS must contain .hero-mini-roster");
        assertTrue(css.contains(".hero-status-indicator"), "CSS must contain .hero-status-indicator");
        assertTrue(css.contains(".app-footer"), "CSS must contain .app-footer");
        assertTrue(css.contains(".app-footer-container"), "CSS must contain .app-footer-container");
        assertTrue(css.contains(".footer-link"), "CSS must contain .footer-link hover styles");
    }

    @Test
    @DisplayName("Test 4: Frontend source files do not contain hardcoded passwords or credential exposures")
    void testZeroHardcodedPasswordsInFrontend() throws Exception {
        InputStream htmlIs = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(htmlIs.readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(html.contains("Admin@123"), "HTML must NOT contain Admin@123");
        assertFalse(html.contains("password123"), "HTML must NOT contain password123");
        assertFalse(html.contains("Development Fast-Login:"), "HTML must NOT contain Development Fast-Login");

        InputStream jsIs = new ClassPathResource("static/app.js").getInputStream();
        String js = new String(jsIs.readAllBytes(), StandardCharsets.UTF_8);
        assertFalse(js.contains("Admin@123"), "app.js must NOT contain Admin@123");
        assertFalse(js.contains("password123"), "app.js must NOT contain password123");
    }
}
