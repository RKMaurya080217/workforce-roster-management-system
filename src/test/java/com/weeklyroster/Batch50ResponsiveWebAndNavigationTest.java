package com.weeklyroster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

public class Batch50ResponsiveWebAndNavigationTest {

    @Test
    @DisplayName("Test 1: index.html contains viewport meta tag and sidebarMobileBackdrop")
    void testIndexHtmlResponsiveStructure() throws Exception {
        File htmlFile = new File("src/main/resources/static/index.html");
        assertTrue(htmlFile.exists(), "index.html must exist");
        String html = Files.readString(htmlFile.toPath());

        assertTrue(html.contains("<meta name=\"viewport\""), "Must contain viewport meta tag");
        assertTrue(html.contains("id=\"sidebarMobileBackdrop\""), "Must contain sidebarMobileBackdrop");
        assertTrue(html.contains("id=\"appSidebar\""), "Must contain appSidebar");
        assertTrue(html.contains("id=\"mobileMenuBtn\""), "Must contain mobileMenuBtn");
    }

    @Test
    @DisplayName("Test 2: styles.css contains comprehensive mobile media queries and table containment")
    void testStylesCssResponsiveRules() throws Exception {
        File cssFile = new File("src/main/resources/static/styles.css");
        assertTrue(cssFile.exists(), "styles.css must exist");
        String css = Files.readString(cssFile.toPath());

        assertTrue(css.contains("@media (max-width: 1024px)"), "Must support <= 1024px breakpoint");
        assertTrue(css.contains("@media (max-width: 768px)"), "Must support <= 768px breakpoint");
        assertTrue(css.contains("@media (max-width: 480px)"), "Must support <= 480px breakpoint");
        assertTrue(css.contains(".table-responsive") || css.contains(".table-wrap"), "Must contain responsive table wrapper rules");
        assertTrue(css.contains(".sidebar-mobile-backdrop"), "Must style sidebar-mobile-backdrop");
        assertTrue(css.contains("overflow-x: auto"), "Must allow table horizontal scroll");
    }

    @Test
    @DisplayName("Test 3: app.js contains mobile sidebar handlers and scroll resets")
    void testAppJsMobileNavigationLogic() throws Exception {
        File jsFile = new File("src/main/resources/static/app.js");
        assertTrue(jsFile.exists(), "app.js must exist");
        String js = Files.readString(jsFile.toPath());

        assertTrue(js.contains("closeMobileSidebar"), "Must contain closeMobileSidebar function");
        assertTrue(js.contains("openMobileSidebar"), "Must contain openMobileSidebar function");
        assertTrue(js.contains("toggleMobileSidebar"), "Must contain toggleMobileSidebar function");
        assertTrue(js.contains("scrollTo"), "Must reset scroll position on view navigation");
    }
}
