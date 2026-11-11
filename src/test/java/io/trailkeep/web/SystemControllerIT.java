// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * The System screen over HTTP (WP-25): System Admins and Compliance Admins see every job from {@code JOB_RUN} with its
 * last success in {@code app.timezone} and its state against its health indicator (on time, late, never run, or not
 * watched, as the purge is), the outbox's waiting and failed counts with the oldest waiting time, and the scanner (here
 * unavailable: the test profile points clamd at a closed port) and the last import run (none, the only one, or the
 * later requested of two); an Analyst gets 403 and nothing else is shown; anonymous requests go to the identity
 * provider. Rows are rolled back after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SystemControllerIT {

    private static final long SYSTEM_ADMIN = 987_001L;
    private static final long COMPLIANCE_ADMIN = 987_002L;
    private static final long ANALYST = 987_003L;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private Instant evaluated;
    private Instant queued;
    private Instant conflictAt;

    @BeforeEach
    void seed() {
        user(SYSTEM_ADMIN, "SYSTEM_ADMIN");
        user(COMPLIANCE_ADMIN, "COMPLIANCE_ADMIN");
        user(ANALYST, null);
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        evaluated = now.minus(Duration.ofMinutes(5));
        queued = now.minus(Duration.ofHours(3));
        jdbc.update("DELETE FROM JOB_RUN");
        run("sla-evaluator", evaluated, 12);
        run("archive", now.minus(Duration.ofHours(5)), 0);
        run("purge", now.minus(Duration.ofDays(1)), 3);
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX");
        jdbc.update("DELETE FROM IMPORT_ISSUE");
        jdbc.update("DELETE FROM IMPORT_RUN_SUMMARY");
        jdbc.update("DELETE FROM IMPORT_RUN");
        mail(987_101L, "PENDING", queued);
        mail(987_102L, "PENDING", now.minus(Duration.ofMinutes(1)));
        mail(987_103L, "FAILED", now.minus(Duration.ofDays(2)));
        conflictAt = now.minus(Duration.ofHours(1));
        jdbc.update(
                "INSERT INTO AUDIT_EVENT (ID, AT_TS, ORIGIN, ENTITY_TYPE, ENTITY_ID, ACTION)"
                        + " VALUES (-987001, ?, 'UI', 'USER', ?, 'ROLE_CONFLICT')",
                Timestamp.from(conflictAt),
                ANALYST);
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void systemAdminsSeeTheJobsTheOutboxAndTheScanner() throws Exception {
        String page = html();
        assertThat(page)
                .contains("<h1>System</h1>", "<caption>Scheduled jobs, by name</caption>")
                .contains("<th scope=\"row\">SLA evaluator</th>", MINUTE.format(evaluated.atZone(ZoneOffset.UTC)))
                .contains("<td>12</td>", "<td>On time</td>")
                .contains(
                        "<th scope=\"row\">Archive</th>", "<td>Late: it has not succeeded within its grace period</td>")
                .contains(
                        "<th scope=\"row\">Purge</th>", "<td>Not watched by a health check (runs as its own job)</td>")
                .contains("<th scope=\"row\">Reminders</th>", "<th scope=\"row\">Report delivery</th>")
                .contains("<span>Never</span>", "<td>Has not run yet</td>")
                .contains("Waiting to be sent</dt>", "<dd class=\"col-sm-8\">2</dd>", "<dd class=\"col-sm-8\">1</dd>")
                .contains(MINUTE.format(queued.atZone(ZoneOffset.UTC)))
                .contains("clamd does not answer: uploads are refused until it does.")
                .contains("<caption>Role conflicts, oldest first</caption>")
                .contains("<th scope=\"row\">Synthetic System User " + ANALYST + "</th><td>"
                        + MINUTE.format(conflictAt.atZone(ZoneOffset.UTC)) + "</td>")
                .doesNotContain("system-test@example.invalid", "Synthetic system body");
        assertThat(page.indexOf(">Archive<")).isLessThan(page.indexOf(">Purge<"));
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsSeeItTooAndAnEmptyOutboxSaysSo() throws Exception {
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX");
        assertThat(html()).contains("<h1>System</h1>", "Nothing is waiting", "<dd class=\"col-sm-8\">0</dd>");
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void noImportRunSaysSo() throws Exception {
        assertThat(html()).contains("<h2>Last import run</h2><p>No import has been run.</p>");
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void theOnlyImportRunIsShownWithoutALoadedCountBeforeLoading() throws Exception {
        Instant requested = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
        importRun(987_201L, "IMPORT", "DRY_RUN", requested, 0);

        assertThat(html())
                .contains("<dd class=\"col-sm-8\">987201 (import)</dd>")
                .contains("<dd class=\"col-sm-8\">Dry run done, waiting for sign-off</dd>")
                .contains("<dd class=\"col-sm-8\">" + MINUTE.format(requested.atZone(ZoneOffset.UTC)) + "</dd>")
                .contains("<dd class=\"col-sm-8\">"
                        + MINUTE.format(requested.plus(Duration.ofMinutes(30)).atZone(ZoneOffset.UTC)) + "</dd>")
                .contains("<dd class=\"col-sm-8\">Not loaded yet</dd>")
                .doesNotContain("No import has been run.", "synthetic-source");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void ofTwoImportRunsTheLaterRequestedIsShown() throws Exception {
        Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        importRun(987_203L, "IMPORT", "RECONCILED", now.minus(Duration.ofDays(3)), 1_250);
        importRun(987_202L, "RELINK", "LOADED", now.minus(Duration.ofHours(1)), 42);

        assertThat(html())
                .contains("<dd class=\"col-sm-8\">987202 (owner relink)</dd>")
                .contains("<dd class=\"col-sm-8\">Loaded</dd>", "<dd class=\"col-sm-8\">42</dd>")
                .doesNotContain("987203", "Loaded and reconciled");
    }

    @Test
    @WithAppUser(id = ANALYST)
    void anyoneElseIsForbidden() throws Exception {
        String page = mvc.perform(get("/admin/system"))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page).doesNotContain("SLA evaluator", "Waiting to be sent");
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/admin/system")).andExpect(status().isFound());
    }

    private String html() throws Exception {
        return mvc.perform(get("/admin/system"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString()
                .replaceAll(">\\s+<", "><");
    }

    private void user(long id, @Nullable String role) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "system-subject-" + id,
                "Synthetic System User " + id);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role);
        }
    }

    private void run(String job, Instant at, int items) {
        jdbc.update(
                "INSERT INTO JOB_RUN (JOB_NAME, LAST_SUCCESS_AT, LAST_ITEMS) VALUES (?, ?, ?)",
                job,
                Timestamp.from(at),
                items);
    }

    private void importRun(long id, String kind, String status, Instant requested, long rowsLoaded) {
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, ROWS_LOADED,"
                        + " UPDATED_AT) VALUES (?, ?, 'synthetic-source', ?, ?, ?, ?, ?)",
                id,
                kind,
                status,
                SYSTEM_ADMIN,
                Timestamp.from(requested),
                rowsLoaded,
                Timestamp.from(requested.plus(Duration.ofMinutes(30))));
    }

    private void mail(long id, String status, Instant at) {
        jdbc.update(
                "INSERT INTO NOTIFICATION_OUTBOX (ID, RECIPIENT, TEMPLATE_ID, SUBJECT, BODY_TEXT, BODY_HTML, STATUS,"
                        + " NEXT_ATTEMPT_AT, CREATED_AT) VALUES (?, 'system-test@example.invalid', 'system-test',"
                        + " 'Synthetic system test', 'Synthetic system body', '<p>Synthetic system body</p>', ?, ?, ?)",
                id,
                status,
                Timestamp.from(at),
                Timestamp.from(at));
    }
}
