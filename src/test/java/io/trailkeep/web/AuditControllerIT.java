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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Compliance Admin audit screen over HTTP (#274, BUILD_PLAN §3.2 "View audit across all cases"): the role gate
 * for every role (a Compliance Admin only; a System Admin is refused even when they also hold Compliance Admin), the
 * filters and paging over events seeded in 2001 so no other test's events interfere, a redacted value shown only as
 * its length (neither its plaintext nor its HMAC on the page), stored text escaped, and the range bounds. Permissions
 * come from the database; rows are rolled back after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class AuditControllerIT {

    private static final long COMPLIANCE_ADMIN = 974_001L;
    private static final long SYSTEM_ADMIN = 974_002L;
    private static final long BOTH_ADMINS = 974_003L;
    private static final long READER = 974_004L;
    private static final long ANALYST = 974_005L;
    private static final long MANAGER = 974_006L;
    private static final long NO_ROLE = 974_007L;
    private static final long TEAM = 974_100L;

    private static final String SUBJECT = "Synthetic subject naming a person";
    private static final String OLD_SUBJECT = "Earlier synthetic subject";
    private static final String MARKUP = "<script>alert(1)</script>";
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
    private String hmac;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Audit Team')", TEAM);
        user(COMPLIANCE_ADMIN, GlobalRole.COMPLIANCE_ADMIN);
        user(SYSTEM_ADMIN, GlobalRole.SYSTEM_ADMIN);
        user(BOTH_ADMINS, GlobalRole.COMPLIANCE_ADMIN, GlobalRole.SYSTEM_ADMIN);
        user(READER);
        user(ANALYST);
        user(MANAGER);
        user(NO_ROLE);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(READER, TEAM, TeamRole.READER);
        seed.member(ANALYST, TEAM, TeamRole.ANALYST);
        seed.member(MANAGER, TEAM, TeamRole.MANAGER);
        number = seed.caseRow(TEAM, ANALYST, Sensitivity.STANDARD, CaseStatus.OPEN);
        long caseId = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?", Long.class, number.toString()));
        AuditChanges edit = recorder.diff(new Snapshot("Old title", OLD_SUBJECT), new Snapshot(MARKUP, SUBJECT));
        // The stored HMAC, which must never reach the page; "absent" if redaction did not run, as in a plant.
        hmac = HEX64.matcher(Objects.requireNonNull(edit.newValuesJson()))
                .results()
                .findFirst()
                .map(MatchResult::group)
                .orElse("absent");
        event(-974_001L, "2001-03-10T09:00:00Z", "Synthetic Case Worker", caseId, "CASE", caseId, "CASE_UPDATED", edit);
        event(
                -974_002L,
                "2001-03-11T09:00:00Z",
                "Synthetic Signer",
                null,
                "USER",
                ANALYST,
                "LOGIN",
                AuditChanges.none());
        event(-974_003L, "2001-03-12T09:00:00Z", null, null, "TEAM", TEAM, "TEAM_CREATED", AuditChanges.none());
        for (int i = 0; i < 55; i++) {
            event(
                    -974_100L - i,
                    "2001-04-01T08:00:00Z",
                    "Synthetic Bulk",
                    null,
                    "USER",
                    (long) i,
                    "LOGIN",
                    AuditChanges.none());
        }
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsSeeCaselessAndCaseEventsWithRedactedValuesAsLengthsOnly() throws Exception {
        String page = html(audit("2001-03-01", "2001-03-31"));

        assertThat(page)
                .contains("<h1>Audit trail</h1>", "Audit events, newest first</caption>", "3 events found.")
                .contains("Synthetic Signer", "<td>LOGIN</td>", "USER " + ANALYST, "TEAM_CREATED", "The system")
                .contains("2001-03-10 09:00:00", "datetime=\"2001-03-10T09:00:00Z\"")
                .contains("href=\"/cases/" + number + "\"")
                .contains("subject: redacted (" + OLD_SUBJECT.length() + " characters) → redacted (" + SUBJECT.length()
                        + " characters)")
                .contains("title: Old title → &lt;script&gt;alert(1)&lt;/script&gt;")
                .doesNotContain(SUBJECT, OLD_SUBJECT, hmac, MARKUP);
        assertThat(HEX64.matcher(page).find()).as("no HMAC of any value").isFalse();
        assertThat(page.indexOf("<td>TEAM_CREATED</td>"))
                .as("newest first")
                .isLessThan(page.indexOf("<td>CASE_UPDATED</td>"));
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void eachFilterNarrowsTheEvents() throws Exception {
        assertThat(html(audit("2001-03-01", "2001-03-31").param("actor", "signer")))
                .contains("1 event found.", "Synthetic Signer")
                .doesNotContain("Synthetic Case Worker");
        assertThat(html(audit("2001-03-01", "2001-03-31").param("entity", "team")))
                .contains("1 event found.", "TEAM_CREATED");
        assertThat(html(audit("2001-03-01", "2001-03-31").param("action", "case_updated")))
                .contains("1 event found.", "Synthetic Case Worker");
        assertThat(html(audit("2001-03-01", "2001-03-31")
                        .param("case", number.toString().toLowerCase(Locale.ROOT))))
                .contains("1 event found.", "CASE_UPDATED");
        assertThat(html(audit("2001-03-11", "2001-03-11"))).contains("1 event found.", "Synthetic Signer");
        assertThat(html(audit("2001-03-01", "2001-03-31").param("case", "SEED-2001-999999")))
                .contains("No case has the number SEED-2001-999999.")
                .doesNotContain("<table");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void fiftyEventsAPageWithLinksThatKeepTheFilters() throws Exception {
        String first = html(audit("2001-04-01", "2001-04-01").param("actor", "bulk"));
        assertThat(first)
                .contains("55 events found. Page 1 of 2.", "rel=\"next\"")
                .doesNotContain("rel=\"prev\"");
        assertThat(rows(first)).isEqualTo(50);
        assertThat(first)
                .contains("href=\"/admin/audit?from=2001-04-01&amp;to=2001-04-01&amp;actor=bulk&amp;entity="
                        + "&amp;action=&amp;case=&amp;page=2\"");

        String second =
                html(audit("2001-04-01", "2001-04-01").param("actor", "bulk").param("page", "2"));
        assertThat(second).contains("Page 2 of 2.", "rel=\"prev\"").doesNotContain("rel=\"next\"");
        assertThat(rows(second)).isEqualTo(5);
        assertThat(html(audit("2001-04-01", "2001-04-01").param("actor", "bulk").param("page", "99")))
                .as("a page past the end shows the last")
                .contains("Page 2 of 2.");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theRangeIsRequiredAndBounded() throws Exception {
        assertThat(html(get("/admin/audit")))
                .as("no range given: the last seven days")
                .contains("type=\"date\"");
        String tooLong = mvc.perform(audit("2001-03-01", "2001-04-01"))
                .andExpect(status().isUnprocessableContent())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(tooLong)
                .contains("aria-invalid=\"true\"", "the range must be at most 31 days")
                .doesNotContain("<table");
        mvc.perform(audit("2001-03-02", "2001-03-01")).andExpect(status().isUnprocessableContent());
        mvc.perform(audit("2001-03-01", "2001-03-31")).andExpect(status().isOk());
        mvc.perform(audit("2001-13-01", "2001-03-31")).andExpect(status().isUnprocessableContent());
        mvc.perform(audit("2001-03-01", "2001-03-31").param("action", "LOGIN%"))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(audit("2001-03-01", "2001-03-31").param("page", "0")).andExpect(status().isUnprocessableContent());
        mvc.perform(audit("2001-03-01", "2001-03-31").param("page", String.valueOf(AuditController.MAX_PAGE + 1)))
                .andExpect(status().isUnprocessableContent());
        mvc.perform(audit("2001-03-01", "2001-03-31").param("actor", "x".repeat(201)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsAreRefused() throws Exception {
        refused();
    }

    @Test
    @WithAppUser(
            id = BOTH_ADMINS,
            globalRole = {GlobalRole.COMPLIANCE_ADMIN, GlobalRole.SYSTEM_ADMIN})
    void systemAdminsWhoAlsoHoldComplianceAdminAreRefused() throws Exception {
        refused();
    }

    @Test
    @WithAppUser(id = MANAGER, team = TEAM, teamRole = TeamRole.MANAGER)
    void managersAreRefused() throws Exception {
        refused();
    }

    @Test
    @WithAppUser(id = ANALYST, team = TEAM, teamRole = TeamRole.ANALYST)
    void analystsAreRefused() throws Exception {
        refused();
    }

    @Test
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void readersAreRefused() throws Exception {
        refused();
    }

    @Test
    @WithAppUser(id = NO_ROLE)
    void peopleWithNoRoleAreRefused() throws Exception {
        refused();
    }

    private void refused() throws Exception {
        String body = mvc.perform(audit("2001-03-01", "2001-03-31"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(body).doesNotContain("Synthetic Signer", "Audit events");
    }

    private static MockHttpServletRequestBuilder audit(String from, String to) {
        return get("/admin/audit").param("from", from).param("to", to);
    }

    private String html(MockHttpServletRequestBuilder request) throws Exception {
        return mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static long rows(String page) {
        return Pattern.compile("<th scope=\"row\"><time")
                .matcher(page)
                .results()
                .count();
    }

    private void user(long id, GlobalRole... roles) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                id,
                "audit-subject-" + id,
                "Synthetic Audit User " + id,
                "audit-" + id + "@example.invalid");
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
            @Nullable Long entityId,
            String action,
            AuditChanges changes) {
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_NAME_SNAPSHOT, ORIGIN, CASE_ID, ENTITY_TYPE, ENTITY_ID,"
                        + " ACTION, OLD_VALUES_JSON, NEW_VALUES_JSON) VALUES (?, ?, ?, 'UI', ?, ?, ?, ?, ?, ?)",
                id,
                Timestamp.from(Instant.parse(at)),
                actor,
                caseId,
                entityType,
                entityId,
                action,
                changes.oldValuesJson(),
                changes.newValuesJson());
    }
}
