package com.weeklyroster.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class Batch52DesktopMobileScrollArchitectureTest {

    @Test
    @DisplayName("Batch 52 [1]: Desktop App Shell Viewport Containment in styles.css")
    void testDesktopAppLayoutViewportContainment() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. .app-layout must lock full viewport height and prevent outer window scrolling
        assertTrue(css.contains(".app-layout {"), "styles.css must define .app-layout");
        assertTrue(css.contains("max-height: 100dvh;"), "styles.css must constrain max-height: 100dvh on layout/main/sidebar");
        assertTrue(css.contains("overflow: hidden;"), "styles.css must prevent layout overflow on desktop");

        // 2. .app-sidebar must be pinned and prevent outer expansion
        assertTrue(css.contains(".app-sidebar {"), "styles.css must define .app-sidebar");
        assertTrue(css.contains("align-self: flex-start;"), "styles.css must align sidebar to flex start");

        // 3. .app-main must be the dedicated vertical scroll container
        assertTrue(css.contains("overflow-y: auto;"), ".app-main must be configured with overflow-y: auto");
        assertTrue(css.contains("overflow-x: hidden;"), ".app-main must be configured with overflow-x: hidden");

        // 4. .app-footer must not shrink inside flex layout
        assertTrue(css.contains("flex-shrink: 0;"), ".app-footer must include flex-shrink: 0");
    }

    @Test
    @DisplayName("Batch 52 [2]: Conflicting Legacy Media Query Sidebar Overrides Removed")
    void testConflictingLegacySidebarOverridesRemoved() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // Old buggy overrides used left: -260px in media queries which broke transform hardware acceleration
        assertFalse(css.contains("left: -260px;"), "styles.css must NOT contain legacy left: -260px sidebar rules");
    }

    @Test
    @DisplayName("Batch 52 [3]: Mobile Responsive Architecture and Off-Canvas Drawer Integrity")
    void testMobileResponsiveLayoutAndDrawerIntegrity() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Mobile app-layout and app-main must restore natural page flow (height: auto, overflow-y: visible)
        assertTrue(css.contains("height: auto !important;"), "Mobile media queries must allow natural vertical flow");
        assertTrue(css.contains("overflow-y: visible !important;"), "Mobile media queries must allow natural vertical scroll");

        // 2. Mobile drawer must use hardware accelerated transform
        assertTrue(css.contains("transform: translateX(-100%) !important;"), "Drawer must hide via translateX(-100%)");
        assertTrue(css.contains("transform: translateX(0) !important;"), "Drawer must show via translateX(0)");

        // 3. Mobile backdrop and body lock
        assertTrue(css.contains(".sidebar-mobile-backdrop.active"), "Mobile backdrop must have active state");
        assertTrue(css.contains("body.sidebar-mobile-locked"), "Body must lock scroll when drawer is open");
    }

    @Test
    @DisplayName("Batch 52 [4]: app.js DOM Registration and Scroll Reset on View Navigation")
    void testAppJsDomRegistrationAndScrollReset() throws Exception {
        InputStream is = new ClassPathResource("static/app.js").getInputStream();
        String js = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. DOM registration for appMain and appContent
        assertTrue(js.contains("appMain: document.querySelector(\".app-main\")"), "dom object must register appMain");
        assertTrue(js.contains("appContent: document.querySelector(\".app-content-viewport\")"), "dom object must register appContent");

        // 2. navigateTo must reset scroll on both appMain and appContent
        assertTrue(js.contains("if (dom.appMain)"), "navigateTo must check dom.appMain");
        assertTrue(js.contains("dom.appMain.scrollTop = 0;"), "navigateTo must reset dom.appMain.scrollTop to 0");
        assertTrue(js.contains("if (dom.appContent)"), "navigateTo must check dom.appContent");
        assertTrue(js.contains("dom.appContent.scrollTop = 0;"), "navigateTo must reset dom.appContent.scrollTop to 0");
    }

    @Test
    @DisplayName("Batch 52 [5]: Static Assets Version Cache-Buster v=2.4.0 & No Duplicate Scripts")
    void testIndexHtmlCacheBustingAndScriptIntegrity() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. Verify v=2.4.0 cache busters
        assertTrue(html.contains("styles.css?v=2.4.0"), "index.html must reference styles.css?v=2.4.0");
        assertTrue(html.contains("app.js?v=2.4.0"), "index.html must reference app.js?v=2.4.0");
        assertTrue(html.contains("enterprise-app.js?v=2.4.0"), "index.html must reference enterprise-app.js?v=2.4.0");

        // 2. Verify duplicate premature scripts are eliminated
        assertFalse(html.contains("<script src=\"/app.js\"></script>"), "index.html must not contain un-versioned premature app.js");
        assertFalse(html.contains("<script src=\"/enterprise-app.js\"></script>"), "index.html must not contain un-versioned premature enterprise-app.js");
    }
}
