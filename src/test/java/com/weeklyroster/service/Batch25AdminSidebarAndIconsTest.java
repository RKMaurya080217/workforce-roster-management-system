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
    @DisplayName("Admin Sidebar: Primary Navigation must contain dashboard, roster, employees, and unified approvals")
    void testAdminPrimaryNavStructure() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("const ADMIN_PRIMARY_NAV = ["), "Must define ADMIN_PRIMARY_NAV array");
        assertTrue(js.contains("id: \"dashboard\""), "Must include dashboard in primary nav");
        assertTrue(js.contains("id: \"roster\""), "Must include weekly roster in primary nav");
        assertTrue(js.contains("id: \"employees\""), "Must include employees in primary nav");
        assertTrue(js.contains("id: \"approvals\""), "Must include unified approvals in primary nav");
    }

    @Test
    @DisplayName("Admin Sidebar: More Menu must contain the secondary management items")
    void testAdminMoreNavStructure() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("const ADMIN_MORE_NAV = ["), "Must define ADMIN_MORE_NAV array");
        assertTrue(js.contains("id: \"analytics\""), "Must include roster analytics in more menu");
        assertTrue(js.contains("id: \"validation\""), "Must include conflict validator in more menu");
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
        assertTrue(js.contains("\"#/approvals\""), "Must support #/approvals");
        assertTrue(js.contains("\"#/roster-analytics\""), "Must support #/roster-analytics");
        assertTrue(js.contains("\"#/conflict-validator\""), "Must support #/conflict-validator");
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
    void testIconSystemIntegrity() throws Exception {
        String js = Files.readString(APP_JS, StandardCharsets.UTF_8);

        assertTrue(js.contains("const WRMS_ICONS = {"), "WRMS_ICONS object must be declared");
        assertTrue(js.contains("window.WRMS_ICONS = WRMS_ICONS"), "WRMS_ICONS must be attached to window");
        assertTrue(js.contains("wrms-icon"), "Icons must have standard CSS class");

        // Verify key icon keys exist
        assertTrue(js.contains("dashboard:"), "Must have dashboard icon");
        assertTrue(js.contains("roster:"), "Must have roster icon");
        assertTrue(js.contains("employees:"), "Must have employees icon");
        assertTrue(js.contains("approvals:"), "Must have approvals icon");
        assertTrue(js.contains("analytics:"), "Must have analytics icon");
        assertTrue(js.contains("validation:"), "Must have validation icon");
        assertTrue(js.contains("fileExcel:"), "Must have Excel icon");
        assertTrue(js.contains("filePdf:"), "Must have PDF icon");
        assertTrue(js.contains("fileCsv:"), "Must have CSV icon");
    }

    @Test
    @DisplayName("HTML & CSS: Zero external CDN icon fonts used")
    void testZeroExternalCdnDependencies() throws Exception {
        String html = Files.readString(INDEX_HTML, StandardCharsets.UTF_8);
        String css = Files.readString(STYLES_CSS, StandardCharsets.UTF_8);

        assertFalse(html.contains("font-awesome"), "No FontAwesome CDN in index.html");
        assertFalse(html.contains("bootstrap-icons"), "No Bootstrap Icons CDN in index.html");
        assertFalse(html.contains("ionicons"), "No Ionicons CDN in index.html");

        assertFalse(css.contains("fontawesome"), "No FontAwesome in styles.css");
        assertFalse(css.contains("bootstrap-icons"), "No Bootstrap Icons in styles.css");
    }
}
