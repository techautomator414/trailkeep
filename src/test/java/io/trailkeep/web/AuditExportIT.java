// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.audit.Redacted;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code GET /admin/audit/export.csv} (#274, BUILD_PLAN §9 "audit export for Compliance Admins"): a Compliance Admin's
 * export is a UTF-8 CSV attachment with a byte order mark, never cached or sniffed, holding the screen's filtered
 * events newest first with their case numbers, a redacted value as its length only and a formula defused, and it is
 * audited ({@code AUDIT_EXPORTED}, with the row count and the filter); a filter over {@code app.export.max-rows} (here
 * 3) and a range problem are 422 on the screen with nothing exported or audited; anyone else is 403 and nothing is
 * audited. Events are seeded in 2001 so no other test's events interfere; rows are rolled back after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "app.export.max-rows=3")
@Transactional
class AuditExportIT {

    private static final long COMPLIANCE_ADMIN = 976_001L;
    private static final long SYSTEM_ADMIN = 976_002L;
    private static final long ANALYST = 976_003L;
    private static final long TEAM = 976_100L;

    private static final String SUBJECT = "Synthetic subject naming a person";
    private static final String FORMULA = "=HYPERLINK(\"x\") Synthetic Signer";
    private static final Pattern HEX64 = Pattern.compile("[0-9a-f]{64}");

    /** A snapshot shaped like a case's, with the subject redacted as the case module redacts it. */
    record Snapshot(String title, @Redacted String subject) {}

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AuditRecorder recorder;

    private CaseNumber number;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Audit Export Team')", TEAM);
        user(COMPLIANCE_ADMIN, GlobalRole.COMPLIANCE_ADMIN);
        user(SYSTEM_ADMIN, GlobalRole.SYSTEM_ADMIN);
        user(ANALYST);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        number = seed.caseRow(TEAM, ANALYST, Sensitivity.STANDARD, CaseStatus.OPEN);
        long caseId = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?", Long.class, number.toString()));
        AuditChanges edit = recorder.diff(new Snapshot("Old title", "Earlier"), new Snapshot("New title", SUBJECT));
        event(-976_001L, "2001-03-10T09:00:00Z", "Synthetic Case Worker", caseId, "CASE", "CASE_UPDATED", edit);
        event(-976_002L, "2001-03-11T09:00:00Z", FORMULA, null, "USER", "LOGIN", AuditChanges.none());
        event(-976_003L, "2001-03-12T09:00:00Z", null, null, "TEAM", "TEAM_CREATED", AuditChanges.none());
        for (int i = 0; i < 4; i++) {
            event(-976_100L - i, "2001-04-01T08:00:00Z", "Synthetic Bulk", null, "USER", "LOGIN", AuditChanges.none());
        }
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsGetAnAuditedCsvWithRedactedValuesAsLengthsAndFormulasDefused() throws Exception {
        MockHttpServletResponse response = mvc.perform(export("2001-03-01", "2001-03-31"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse();
        String body = response.getContentAsString(StandardCharsets.UTF_8);

        assertThat(response.getContentType()).isEqualTo("text/csv;charset=UTF-8");
        assertThat(response.getHeader("Content-Disposition"))
                .matches("attachment; filename=\"audit-\\d{8}-\\d{4}\\.csv\"");
        assertThat(response.getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(response.getHeader("Cache-Control")).contains("no-store");
        assertThat(body.split("\r\n", -1))
                .containsExactly(
                        "﻿\"Time (UTC)\",\"Actor\",\"Origin\",\"Entity type\",\"Entity id\",\"Action\","
                                + "\"Case number\",\"Changes\",\"Request id\"",
                        "\"2001-03-12T09:00:00Z\",\"\",\"UI\",\"TEAM\",\"\",\"TEAM_CREATED\",\"\",\"\",\"\"",
                        "\"2001-03-11T09:00:00Z\",\"'=HYPERLINK(\"\"x\"\") Synthetic Signer\",\"UI\",\"USER\",\"\","
                                + "\"LOGIN\",\"\",\"\",\"\"",
                        "\"2001-03-10T09:00:00Z\",\"Synthetic Case Worker\",\"UI\",\"CASE\",\"\",\"CASE_UPDATED\",\""
                                + number + "\",\"title: Old title → New title; subject: redacted (7 characters)"
                                + " → redacted (" + SUBJECT.length() + " characters)\",\"\"",
                        "");
        assertThat(body).doesNotContain(SUBJECT, "Earlier");
        assertThat(HEX64.matcher(body).find()).as("no HMAC of any value").isFalse();

        List<Map<String, Object>> audited = exports();
        assertThat(audited).hasSize(1);
        assertThat(audited.getFirst())
                .containsEntry("ENTITY_TYPE", "AUDIT_TRAIL")
                .containsEntry("ACTOR_NAME_SNAPSHOT", "Test User");
        assertThat((String) audited.getFirst().get("NEW_VALUES_JSON"))
                .contains("\"rows\":3", "\"query\":\"from=2001-03-01&to=2001-03-31&actor=&entity=&action=&case=\"");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theScreenOffersTheExportWithItsFilters() throws Exception {
        String page = mvc.perform(get("/admin/audit")
                        .param("from", "2001-03-01")
                        .param("to", "2001-03-31")
                        .param("action", "login"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(page)
                .contains(
                        "action=\"/admin/audit/export.csv\"", "<input type=\"hidden\" name=\"action\" value=\"LOGIN\">")
                .contains("Exports the one matching event; redacted values stay redacted. The export is audited.");
        assertThat(exports()).as("showing the screen records nothing").isEmpty();
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void filtersOverTheCapOrABadRangeAreUnprocessableAndNothingIsAudited() throws Exception {
        String page = mvc.perform(export("2001-04-01", "2001-04-01"))
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page)
                .contains("<h1>Audit trail</h1>", "4 events found.")
                .containsPattern(
                        "role=\"alert\" tabindex=\"-1\" data-tk-error-summary>\\s*<p id=\"tk-audit-export-error\""
                                + " class=\"mb-0\">Not exported: 4 events match, more than the 3 one export may hold.")
                .contains("aria-describedby=\"tk-audit-export-error tk-audit-export-hint\"");
        mvc.perform(export("2001-03-01", "2001-04-30")).andExpect(status().isUnprocessableContent());

        assertThat(exports()).isEmpty();
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsAreRefusedAndNothingIsAudited() throws Exception {
        mvc.perform(export("2001-03-01", "2001-03-31")).andExpect(status().isForbidden());
        assertThat(exports()).isEmpty();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsAreRefusedAndNothingIsAudited() throws Exception {
        mvc.perform(export("2001-03-01", "2001-03-31")).andExpect(status().isForbidden());
        assertThat(exports()).isEmpty();
    }

    private List<Map<String, Object>> exports() {
        return jdbc.queryForList(
                "SELECT ENTITY_TYPE, ACTOR_NAME_SNAPSHOT, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE ACTION = 'AUDIT_EXPORTED' AND ACTOR_ID IN (?, ?, ?)",
                COMPLIANCE_ADMIN,
                SYSTEM_ADMIN,
                ANALYST);
    }

    private static MockHttpServletRequestBuilder export(String from, String to) {
        return get("/admin/audit/export.csv").param("from", from).param("to", to);
    }

    private void user(long id, GlobalRole... roles) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                id,
                "audit-export-subject-" + id,
                "Synthetic Audit Export User " + id,
                "audit-export-" + id + "@example.invalid");
        for (GlobalRole role : roles) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role.name());
        }
    }

    private void event(
            long id,
            String at,
            @Nullable String actor,
            @Nullable Long caseId,
            String entityType,
            String action,
            AuditChanges changes) {
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_NAME_SNAPSHOT, ORIGIN, CASE_ID, ENTITY_TYPE, ACTION,"
                        + " OLD_VALUES_JSON, NEW_VALUES_JSON) VALUES (?, ?, ?, 'UI', ?, ?, ?, ?, ?)",
                id,
                Timestamp.from(Instant.parse(at)),
                actor,
                caseId,
                entityType,
                action,
                changes.oldValuesJson(),
                changes.newValuesJson());
    }
}
