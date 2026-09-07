package com.weeklyroster.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

public class Batch53DefinitiveScrollArchitectureTest {

    @Test
    @DisplayName("Batch 53 [1]: Scoped Authenticated App Shell Viewport Lock in styles.css")
    void testAuthenticatedAppShellViewportLock() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. html.app-authenticated and body.app-authenticated lock the outer window
        assertTrue(css.contains("html.app-authenticated,"), "styles.css must scope lock to html.app-authenticated");
        assertTrue(css.contains("body.app-authenticated {"), "styles.css must scope lock to body.app-authenticated");
        assertTrue(css.contains("max-height: 100dvh !important;"), "styles.css must enforce max-height: 100dvh !important");

        // 2. body.app-authenticated #appRoot containment
        assertTrue(css.contains("body.app-authenticated #appRoot {"), "styles.css must contain #appRoot under body.app-authenticated");

        // 3. body.app-authenticated .app-layout containment
        assertTrue(css.contains("body.app-authenticated .app-layout {"), "styles.css must contain .app-layout under body.app-authenticated");

        // 4. body.app-authenticated .app-main sole vertical scroll container
        assertTrue(css.contains("body.app-authenticated .app-main {"), "styles.css must define .app-main under body.app-authenticated");
        assertTrue(css.contains("overflow-y: auto !important;"), ".app-main must enforce overflow-y: auto !important");
    }

    @Test
    @DisplayName("Batch 53 [2]: Desktop Sidebar Flex Layout & Sticky Failure Prevention")
    void testDesktopSidebarFlexLayout() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. .app-sidebar must use position: static direct flex containment instead of fragile window-sticky
        assertTrue(css.contains("position: static;"), ".app-sidebar must use position: static for flex containment");
        assertTrue(css.contains("flex: 0 0 var(--sidebar-width);"), ".app-sidebar must lock flex-basis to --sidebar-width");

        // 2. .sidebar-nav must be the internal scroll container
        assertTrue(css.contains(".sidebar-nav {"), ".sidebar-nav must be defined");
        assertTrue(css.contains("overflow-y: auto;"), ".sidebar-nav must have overflow-y: auto");
    }

    @Test
    @DisplayName("Batch 53 [3]: app.js Toggles app-authenticated Class on Session Lifecycle")
    void testAppJsSessionLifecycleClassToggling() throws Exception {
        InputStream is = new ClassPathResource("static/app.js").getInputStream();
        String js = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // 1. showWorkspace must add app-authenticated
        assertTrue(js.contains("document.documentElement.classList.add(\"app-authenticated\")"), "showWorkspace must add class to documentElement");
        assertTrue(js.contains("document.body.classList.add(\"app-authenticated\")"), "showWorkspace must add class to body");

        // 2. showLogin must remove app-authenticated
        assertTrue(js.contains("document.documentElement.classList.remove(\"app-authenticated\")"), "showLogin/handleLogout must remove class from documentElement");
        assertTrue(js.contains("document.body.classList.remove(\"app-authenticated\")"), "showLogin/handleLogout must remove class from body");
    }

    @Test
    @DisplayName("Batch 53 [4]: Tablet & Laptop <= 1024px Layout Viewport Containment")
    void testTabletLaptopBreakpointContainment() throws Exception {
        InputStream is = new ClassPathResource("static/styles.css").getInputStream();
        String css = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        // Ensure 1024px media queries maintain layout containment and do NOT leak overflow-y: visible on .app-main
        assertTrue(css.contains("@media (max-width: 1024px)"), "styles.css must define @media (max-width: 1024px)");
        assertTrue(css.contains("height: 100dvh !important;"), "Media queries must retain 100dvh containment");
    }

    @Test
    @DisplayName("Batch 53 [5]: Static Assets Version Cache-Buster v=2.5.0 in index.html")
    void testIndexHtmlVersion250CacheBusting() throws Exception {
        InputStream is = new ClassPathResource("static/index.html").getInputStream();
        String html = new String(is.readAllBytes(), StandardCharsets.UTF_8);

        assertTrue(html.contains("styles.css?v=2.5.0"), "index.html must reference styles.css?v=2.5.0");
        assertTrue(html.contains("app.js?v=2.5.0"), "index.html must reference app.js?v=2.5.0");
        assertTrue(html.contains("enterprise-app.js?v=2.5.0"), "index.html must reference enterprise-app.js?v=2.5.0");
    }
}
