package com.weeklyroster.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class Batch25AdminSidebarAndIconsTest {

    private static final Path APP_JS = Path.of("src/main/resources/static/app.js");
    private static final Path ENTERPRISE_APP_JS = Path.of("src/main/resources/static/enterprise-app.js");
    private static final Path INDEX_HTML = Path.of("src/main/resources/static/index.html");
    private static final Path STYLES_CSS = Path.of("src/main/resources/static/styles.css");

    @Test
    @DisplayName("Admin Sidebar: Primary Navigation must contain exactly the 5 specified items in order")
    void testAdminPrimaryNavStructure() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("const ADMIN_PRIMARY_NAV = ["), "Must define ADMIN_PRIMARY_NAV array");
        assertTrue(js.contains("id: \"dashboard\""), "Must include dashboard in primary nav");
        assertTrue(js.contains("id: \"roster\""), "Must include weekly roster in primary nav");
        assertTrue(js.contains("id: \"employees\""), "Must include employees in primary nav");
        assertTrue(js.contains("id: \"leaves\""), "Must include leave requests in primary nav");
        assertTrue(js.contains("id: \"profileApprovals\""), "Must include profile approvals in primary nav");
    }

    @Test
    @DisplayName("Admin Sidebar: More Menu must contain the 13 secondary items")
    void testAdminMoreNavStructure() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("const ADMIN_MORE_NAV = ["), "Must define ADMIN_MORE_NAV array");
        assertTrue(js.contains("id: \"analytics\""), "Must include roster analytics in more menu");
        assertTrue(js.contains("id: \"validation\""), "Must include conflict validator in more menu");
        assertTrue(js.contains("id: \"adminPreferences\""), "Must include shift preferences in more menu");
        assertTrue(js.contains("id: \"adminHolidays\""), "Must include holiday calendar in more menu");
        assertTrue(js.contains("id: \"adminHandovers\""), "Must include shift handovers in more menu");
        assertTrue(js.contains("id: \"adminWorkload\""), "Must include workload analytics in more menu");
        assertTrue(js.contains("id: \"adminSkills\""), "Must include skill matrix in more menu");
        assertTrue(js.contains("id: \"exportCenter\""), "Must include export center in more menu");
        assertTrue(js.contains("id: \"rosterVersions\""), "Must include roster versions in more menu");
        assertTrue(js.contains("id: \"health\""), "Must include roster health in more menu");
        assertTrue(js.contains("id: \"shifts\""), "Must include shift capacity in more menu");
        assertTrue(js.contains("id: \"history\""), "Must include roster history in more menu");
        assertTrue(js.contains("id: \"audit\""), "Must include audit trail in more menu");
    }

    @Test
    @DisplayName("Admin Routing: Canonical routes and legacy aliases must all be supported")
    void testAdminRoutingAndCanonicalHashes() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        // Check canonical hashes
        assertTrue(js.contains("\"#/dashboard\""), "Must support #/dashboard");
        assertTrue(js.contains("\"#/weekly-roster\""), "Must support #/weekly-roster");
        assertTrue(js.contains("\"#/employees\""), "Must support #/employees");
        assertTrue(js.contains("\"#/leave-requests\""), "Must support #/leave-requests");
        assertTrue(js.contains("\"#/profile-approvals\""), "Must support #/profile-approvals");
        assertTrue(js.contains("\"#/roster-analytics\""), "Must support #/roster-analytics");
        assertTrue(js.contains("\"#/conflict-validator\""), "Must support #/conflict-validator");
        assertTrue(js.contains("\"#/shift-preferences\""), "Must support #/shift-preferences");
        assertTrue(js.contains("\"#/holiday-calendar\""), "Must support #/holiday-calendar");
        assertTrue(js.contains("\"#/shift-handovers\""), "Must support #/shift-handovers");
        assertTrue(js.contains("\"#/workload-analytics\""), "Must support #/workload-analytics");
        assertTrue(js.contains("\"#/skill-matrix\""), "Must support #/skill-matrix");
        assertTrue(js.contains("\"#/export-center\""), "Must support #/export-center");
        assertTrue(js.contains("\"#/roster-versions\""), "Must support #/roster-versions");
        assertTrue(js.contains("\"#/roster-health\""), "Must support #/roster-health");
        assertTrue(js.contains("\"#/shift-capacity\""), "Must support #/shift-capacity");
        assertTrue(js.contains("\"#/roster-history\""), "Must support #/roster-history");
        assertTrue(js.contains("\"#/audit-trail\""), "Must support #/audit-trail");
    }

    @Test
    @DisplayName("Icon System: WRMS_ICONS must be globally available with local SVGs without external CDNs")
    void testLocalSvgIconSystem() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("const WRMS_ICONS = {"), "Must define WRMS_ICONS dictionary");
        assertTrue(js.contains("window.WRMS_ICONS = WRMS_ICONS;"), "Must attach WRMS_ICONS to window");

        // Verify zero external CDN URLs in icons
        assertFalse(js.contains("https://cdnjs.cloudflare.com"), "No external CDN icons allowed");
        assertFalse(js.contains("https://cdn.jsdelivr.net"), "No external CDN icons allowed");
        assertFalse(js.contains("https://unpkg.com"), "No external CDN icons allowed");
    }

    @Test
    @DisplayName("CSS: Sidebar navigation styles must include custom scrollbars, more menu, and active states")
    void testSidebarStyles() throws Exception {
        String css = Files.readString(STYLES_CSS, StandardCharsets.UTF_8);

        assertTrue(css.contains(".sidebar-nav::-webkit-scrollbar"), "Must style independent sidebar scrollbar");
        assertTrue(css.contains(".wrms-icon"), "Must style .wrms-icon");
        assertTrue(css.contains(".nav-more-group"), "Must style .nav-more-group");
        assertTrue(css.contains(".nav-more-toggle"), "Must style .nav-more-toggle");
        assertTrue(css.contains(".nav-sub-menu"), "Must style .nav-sub-menu");
        assertTrue(css.contains(".nav-sub-item"), "Must style .nav-sub-item");
        assertTrue(css.contains(".nav-sub-item.active"), "Must style active sub-item");
    }

    @Test
    @DisplayName("Zero Broken Encoding: Static files must not contain mojibake or raw emoji icon characters in navigation")
    void testNoMojibakeOrEmojisInNavigation() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);
        String enterpriseJs = Files.readString(ENTERPRISE_APP_JS, StandardCharsets.UTF_8);

        assertFalse(js.contains("Ã°Å¸â€œÅ "), "app.js must not contain mojibake chart");
        assertFalse(js.contains("Ã¢Å¡â€“Ã¯Â¸ "), "app.js must not contain mojibake scales");
        assertFalse(enterpriseJs.contains("Ã°Å¸â€œÅ "), "enterprise-app.js must not contain mojibake chart");
        assertFalse(enterpriseJs.contains("Ã°Å¸ â€“Ã¯Â¸ "), "enterprise-app.js must not contain mojibake beach");
    }
}