// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
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
import java.util.ArrayList;
import java.util.List;
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
 * The Import runs screen over HTTP (WP-26 part s1): Compliance Admins and System Admins see the runs newest first, each
 * linking to its page, or that there are none; a run's page shows who requested it and when, the recorded scanning
 * mode, its issues per class (fatal first) and its dry-run figures with teams by name and the forecast day as a date,
 * and says so when there are none; a signed-off run shows who signed it off. It never shows the source id or a key
 * hash. An unknown or malformed run is 404; anyone else gets 403 on both pages, and anonymous requests go to the
 * identity provider. The closure worklist (WP-26 part y3) links the run's CLOSED_IN_SOURCE cases 20 to a page, and
 * only counts those the viewer may not read. Rows are rolled back after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ImportRunControllerIT {

    private static final long SYSTEM_ADMIN = 988_001L;
    private static final long COMPLIANCE_ADMIN = 988_002L;
    private static final long MANAGER = 988_003L;
    private static final long TEAM = 988_050L;
    private static final long RUN = 988_101L;
    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private Instant requested;

    @BeforeEach
    void seed() {
        user(SYSTEM_ADMIN, "SYSTEM_ADMIN");
        user(COMPLIANCE_ADMIN, "COMPLIANCE_ADMIN");
        user(MANAGER, null);
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Import Team')", TEAM);
        for (String table : List.of(
                "IMPORT_LEDGER",
                "CASE_IMPORTED_EVENT",
                "IMPORT_STG_EVENT",
                "IMPORT_STG_ATTACHMENT",
                "IMPORT_STG_COMMENT",
                "IMPORT_STG_CASE",
                "IMPORT_STG_PERSON",
                "IMPORT_RUN_CHECKPOINT",
                "IMPORT_ISSUE",
                "IMPORT_RUN_SUMMARY",
                "IMPORT_RUN")) {
            // Test-only cleanup over the fixed table names of this list, rolled back with the test.
            jdbc.update("DELETE FROM " + table);
        }
        requested = Instant.now().minus(Duration.ofHours(2)).truncatedTo(ChronoUnit.SECONDS);
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void complianceAdminsSeeTheRunsNewestFirst() throws Exception {
        run(RUN, "RECONCILED", requested.minus(Duration.ofDays(1)));
        run(RUN + 1, "EXTRACTED", requested);

        String page = html("/admin/import-runs");
        assertThat(page)
                .contains("<h1>Import runs</h1>", "<caption>Import runs, most recently requested first</caption>")
                .contains("<th scope=\"row\"><a href=\"/admin/import-runs/988102\">988102 (import)</a></th>"
                        + "<td>Extracted</td><td>" + MINUTE.format(requested.atZone(ZoneOffset.UTC)) + "</td>")
                .contains("<a href=\"/admin/import-runs/988101\">988101 (import)</a>", "Loaded and reconciled")
                .doesNotContain("synthetic-src-9", "No import has been run.");
        assertThat(page.indexOf("988102 (import)")).isLessThan(page.indexOf("988101 (import)"));
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void noRunSaysSo() throws Exception {
        assertThat(html("/admin/import-runs?page=x"))
                .contains("No import has been run.")
                .doesNotContain("<table");
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void oneRunShowsItsIssuesPerClassAndItsFigures() throws Exception {
        run(RUN, "DRY_RUN", requested);
        jdbc.update("UPDATE IMPORT_RUN SET SCAN_MODE = 'EXTERNAL', ROWS_STAGED = 9 WHERE ID = ?", RUN);
        issue(1, "ATTACHMENT", "WARNING", "NOT_SCANNED");
        issue(2, "CASE", "ERROR", "TITLE_MISSING");
        issue(3, "CASE", "ERROR", "TITLE_MISSING");
        issue(4, "PERSON", "WARNING", "AMBIGUOUS_PERSON");
        figure(1, "VISIBILITY_RESTRICTED", Long.toString(TEAM), 4);
        figure(2, "PEOPLE_MATCHED", "OPEN_OWNER", 7);
        figure(3, "SLA_FORECAST_DAY", null, 20_454);
        figure(4, "ISSUES", "CASE/ERROR/TITLE_MISSING", 2);

        String page = html("/admin/import-runs/" + RUN);
        assertThat(page)
                .contains("<h1>Import run 988101</h1>", "<a href=\"/admin/import-runs\">All import runs</a>")
                .contains("<dd class=\"col-sm-8\">" + MINUTE.format(requested.atZone(ZoneOffset.UTC))
                        + " by Synthetic Import User 988001</dd>")
                .contains("<dd class=\"col-sm-8\">Dry run done, waiting for sign-off</dd>")
                .contains("<dd class=\"col-sm-8\">9</dd>", "<dd class=\"col-sm-8\">Not signed off</dd>")
                .contains("<dd class=\"col-sm-8\">External: attachments are stored as not scanned</dd>")
                .contains("<caption>Issues per class, fatal first</caption>")
                .contains("<th scope=\"row\"><code>TITLE_MISSING</code></th><td>Cases</td>"
                        + "<td>Error: the row is not loaded</td><td>2</td>")
                .contains("<th scope=\"row\"><code>AMBIGUOUS_PERSON</code></th><td>People</td>")
                .contains("<p>The SLA forecast is for 2026-01-01.</p>")
                .contains("<th scope=\"row\">People matched</th><td>Owners of open cases</td><td>7</td>")
                .contains("<th scope=\"row\">Restricted cases per team</th><td>Synthetic Import Team</td><td>4</td>")
                .doesNotContain("synthetic-src-9", "cdcdcd", "CASE/ERROR", "SLA_FORECAST_DAY");
        assertThat(page.indexOf("TITLE_MISSING")).isLessThan(page.indexOf("AMBIGUOUS_PERSON"));
        assertThat(page.indexOf("AMBIGUOUS_PERSON")).isLessThan(page.indexOf("NOT_SCANNED"));
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void closureWorklistLinksTheCasesClosedInTheSourceTwentyToAPage() throws Exception {
        run(RUN, "DRY_RUN", requested);
        List<CaseNumber> numbers = closedInSource(21);

        String first = html("/admin/import-runs/" + RUN + "?worklist=x");
        String second = html("/admin/import-runs/" + RUN + "?worklist=2");

        assertThat(first)
                .contains("<h2 id=\"tk-worklist-title\">Closure worklist</h2>")
                .contains("<p>21 cases are open here but closed in the source. Nothing was closed automatically: each"
                        + " owner closes the case through the normal closure flow.</p>")
                .contains("<p>Page 1 of 2.</p>", "<caption>Cases closed in the source, by case number</caption>")
                .contains("<th scope=\"row\"><a href=\"/cases/" + numbers.getFirst() + "\">" + numbers.getFirst()
                        + "</a></th><td>Seed case " + numbers.getFirst().sequence() + "</td>")
                .contains("href=\"/admin/import-runs/" + RUN + "?worklist=2\">Next page</a>")
                .doesNotContain(numbers.getLast().toString(), "not listed");
        assertThat(second)
                .contains("<p>Page 2 of 2.</p>", "/cases/" + numbers.getLast())
                .contains("href=\"/admin/import-runs/" + RUN + "?worklist=1\">Previous page</a>")
                .doesNotContain("/cases/" + numbers.getFirst() + "\"");
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void worklistCasesTheViewerMayNotReadAreOnlyCounted() throws Exception {
        run(RUN, "DRY_RUN", requested);
        List<CaseNumber> numbers = closedInSource(2);

        assertThat(html("/admin/import-runs/" + RUN))
                .contains("<p>2 cases are open here but closed in the source.")
                .contains("<p>2 cases on this page are not listed because you may not read them.</p>")
                .doesNotContain(numbers.getFirst().toString(), numbers.getLast().toString(), "Seed case")
                .doesNotContain("<caption>Cases closed in the source");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void runWithoutDryRunSaysSo() throws Exception {
        run(RUN, "VALIDATED", requested);

        assertThat(html("/admin/import-runs/" + RUN))
                .contains("<dd class=\"col-sm-8\">Mapping validated</dd>", "<p>No issues.</p>")
                .contains("<dd class=\"col-sm-8\">No completed dry run</dd>")
                .contains("<p>No dry run has completed for this run.</p>")
                .doesNotContain("<table");
    }

    @Test
    @WithAppUser(id = COMPLIANCE_ADMIN, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void signedOffRunShowsWhoSignedItOff() throws Exception {
        run(RUN, "DRY_RUN", requested);
        Instant approved = requested.plus(Duration.ofMinutes(45));
        jdbc.update(
                "UPDATE IMPORT_RUN SET SCAN_MODE = 'CLAMD', APPROVED_BY = ?, APPROVED_AT = ? WHERE ID = ?",
                COMPLIANCE_ADMIN,
                Timestamp.from(approved),
                RUN);

        assertThat(html("/admin/import-runs/" + RUN))
                .contains("<dd class=\"col-sm-8\">Signed off, waiting to load</dd>")
                .contains("<dd class=\"col-sm-8\">" + MINUTE.format(approved.atZone(ZoneOffset.UTC))
                        + " by Synthetic Import User 988002</dd>")
                .contains("clamd: every attachment is scanned as it is loaded");
    }

    @Test
    @WithAppUser(id = SYSTEM_ADMIN, globalRole = GlobalRole.SYSTEM_ADMIN)
    void anUnknownOrMalformedRunIsNotFound() throws Exception {
        mvc.perform(get("/admin/import-runs/988999")).andExpect(status().isNotFound());
        mvc.perform(get("/admin/import-runs/twelve")).andExpect(status().isNotFound());
    }

    @Test
    @WithAppUser(id = MANAGER)
    void anyoneElseIsForbidden() throws Exception {
        run(RUN, "DRY_RUN", requested);

        mvc.perform(get("/admin/import-runs")).andExpect(status().isForbidden());
        String page = mvc.perform(get("/admin/import-runs/" + RUN))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(page).doesNotContain("988101", "Dry run done");
    }

    @Test
    void anonymousRequestsGoToTheIdentityProvider() throws Exception {
        mvc.perform(get("/admin/import-runs")).andExpect(status().isFound());
        mvc.perform(get("/admin/import-runs/" + RUN)).andExpect(status().isFound());
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
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
                "import-subject-" + id,
                "Synthetic Import User " + id);
        if (role != null) {
            jdbc.update("INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, ?)", id, role);
        }
    }

    private void run(long id, String status, Instant at) {
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, UPDATED_AT)"
                        + " VALUES (?, 'IMPORT', 'synthetic-src-9', ?, ?, ?, ?)",
                id,
                status,
                SYSTEM_ADMIN,
                Timestamp.from(at),
                Timestamp.from(at.plus(Duration.ofMinutes(30))));
    }

    private void issue(long id, String entity, String severity, String code) {
        jdbc.update(
                "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, KEY_HASH, SEVERITY, CODE, CREATED_AT)"
                        + " VALUES (?, ?, 'DRY_RUN', ?, ?, ?, ?, CURRENT_TIMESTAMP)",
                988_200L + id,
                RUN,
                entity,
                "cd".repeat(31) + id + id,
                severity,
                code);
    }

    /** Open cases of the team, each a {@code CLOSED_IN_SOURCE} issue of the run carrying its id, in id order. */
    private List<CaseNumber> closedInSource(int count) {
        CaseSeed seed = new CaseSeed(jdbc);
        List<CaseNumber> numbers = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            CaseNumber number = seed.caseRow(TEAM, COMPLIANCE_ADMIN, Sensitivity.STANDARD, CaseStatus.OPEN);
            numbers.add(number);
            jdbc.update(
                    "INSERT INTO IMPORT_ISSUE (ID, RUN_ID, STAGE, ENTITY, KEY_HASH, SEVERITY, CODE, DETAIL, CREATED_AT)"
                            + " VALUES (?, ?, 'DRY_RUN', 'CASE', ?, 'WARNING', 'CLOSED_IN_SOURCE', ?,"
                            + " CURRENT_TIMESTAMP)",
                    988_400L + i,
                    RUN,
                    "%064x".formatted(i),
                    seed.caseId(number));
        }
        return numbers;
    }

    private void figure(long id, String metric, @Nullable String dimension, long value) {
        jdbc.update(
                "INSERT INTO IMPORT_RUN_SUMMARY (ID, RUN_ID, METRIC, DIMENSION, METRIC_VALUE) VALUES (?, ?, ?, ?, ?)",
                988_300L + id,
                RUN,
                metric,
                dimension,
                value);
    }
}
