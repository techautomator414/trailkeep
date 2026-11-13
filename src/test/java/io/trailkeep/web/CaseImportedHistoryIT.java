// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.audit.ImportedHistoryService.ImportedEvent;
import io.trailkeep.audit.ImportedHistoryService.Kind;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.HistorySeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.regex.Matcher;
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
import org.springframework.transaction.annotation.Transactional;

/**
 * Imported legacy history on the History page (WP-26 part x, docs/design/importer.md §7.5): imported rows join the
 * audit events in one timeline by time, each marked "Imported" with its import run and the legacy actor as recorded;
 * plain values are shown as stored and escaped, and a redacted value (a description, a subject, a note) stays "set" or
 * "changed" with no text, digest or length; the filter shows everything, everything but the imported history, or only
 * it, and the audit kinds keep their meaning; a colleague who may not read a restricted case gets 404 under every
 * filter, and its imported rows never reach another case's list or count; and the pages of a long mixed timeline
 * follow the merged order exactly.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class CaseImportedHistoryIT {

    private static final long OWNER = 969_001L;
    private static final long COLLEAGUE = 969_002L;
    private static final long TEAM = 969_100L;
    private static final Instant Y2019 = Instant.parse("2019-01-01T10:00:00Z");
    private static final Pattern WHO = Pattern.compile("(Native|Legacy) \\d{3}");
    private static final Pattern DIGEST = Pattern.compile("^[0-9a-f]{64}$");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ImportedHistoryService importedHistory;

    @Autowired
    private EntityManager entityManager;

    private HistorySeed history;
    private CaseSeed seed;
    private CaseNumber standard;
    private long standardId;
    private long run;

    @BeforeEach
    void seed() {
        user(OWNER, "Synthetic Imported Owner");
        user(COLLEAGUE, "Synthetic Imported Colleague");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Imported Team')", TEAM);
        seed = new CaseSeed(jdbc);
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        seed.member(COLLEAGUE, TEAM, TeamRole.ANALYST);
        history = new HistorySeed(jdbc);
        run = history.run(OWNER);
        standard = seed.caseRow(TEAM, OWNER, Sensitivity.STANDARD, CaseStatus.OPEN);
        standardId = seed.caseId(standard);
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void importedRowsAreInterleavedByTimeMarkedAndEscaped() throws Exception {
        fourRows();

        String page = page("");
        assertThat(page).contains("4 events found.", "Synthetic Legacy Clerk", "Not recorded", "Import");
        assertThat(page.indexOf("Case reassigned"))
                .as("newest first, the two sources interleaved")
                .isLessThan(page.indexOf("Assignment changed"));
        assertThat(page.indexOf("Assignment changed")).isLessThan(page.indexOf("Case edited"));
        assertThat(page.indexOf("Case edited")).isLessThan(page.indexOf("Status changed"));
        assertThat(count(page, "<span>Imported</span>"))
                .as("one text marker per imported row")
                .isEqualTo(2);
        assertThat(count(page, "From the legacy tracker, import run " + run)).isEqualTo(2);
        assertThat(page)
                .contains("Status: New → In review")
                .contains("Owner: Old Team Lead → &lt;b&gt;New&lt;/b&gt; Lead")
                .doesNotContain("<b>New</b>");
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void redactedImportedValuesStayRedacted() throws Exception {
        record(Kind.FIELD_CHANGE, "description", null, "SECRETLEGACY-ONE description");
        record(Kind.NOTE, null, null, "SECRETLEGACY-TWO note");
        record(Kind.FIELD_CHANGE, "subject", "SECRETLEGACY-THREE old", "SECRETLEGACY-FOUR new");
        record(Kind.OTHER, "other", "SECRETLEGACY-FIVE", null);
        entityManager.flush();

        String page = page("?kind=imported");
        assertThat(page)
                .contains("4 events found.", "Description: (set)", "Note: (set)", "Subject: (changed)")
                .contains("Other field: (changed)")
                .doesNotContain("SECRETLEGACY", "hmac", "\"length\"");
        List<String> digests = new ArrayList<>();
        for (String column : List.of("OLD_HMAC", "NEW_HMAC")) {
            digests.addAll(jdbc.queryForList(
                    "SELECT " + column + " FROM CASE_IMPORTED_EVENT WHERE CASE_ID = ? AND " + column + " IS NOT NULL",
                    String.class,
                    standardId));
        }
        assertThat(digests)
                .as("the stored rows do hold digests, so the check is not vacuous")
                .hasSize(5);
        assertThat(digests).allMatch(digest -> DIGEST.matcher(digest).matches());
        assertThat(page).doesNotContain(digests.toArray(String[]::new));
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void theFilterIncludesExcludesOrIsolatesImportedRows() throws Exception {
        fourRows();

        assertThat(page("?kind=all")).contains("4 events found.", "Status changed", "Case edited");
        String imported = page("?kind=imported");
        assertThat(imported)
                .containsPattern("value=\"imported\"\\s+selected")
                .contains("2 events found.", "Status changed", "Assignment changed")
                .doesNotContain("Case edited", "Case reassigned");
        String nativeOnly = page("?kind=native");
        assertThat(nativeOnly)
                .containsPattern("value=\"native\"\\s+selected")
                .contains("2 events found.", "Case edited", "Case reassigned")
                .doesNotContain("From the legacy tracker", "Status changed");
        assertThat(page("?kind=fields"))
                .as("an audit kind keeps its meaning: audit actions only")
                .contains("2 events found.", "Case edited")
                .doesNotContain("From the legacy tracker");
        assertThat(page("?kind=comments")).contains("No events found.").doesNotContain("From the legacy tracker");
    }

    @Test
    @WithAppUser(id = COLLEAGUE, team = TEAM, teamRole = TeamRole.ANALYST)
    void restrictedImportedHistoryIs404UnderEveryFilterAndNeverCounted() throws Exception {
        CaseNumber restricted = seed.caseRow(TEAM, OWNER, Sensitivity.RESTRICTED, CaseStatus.OPEN);
        long restrictedId = seed.caseId(restricted);
        for (int i = 0; i < 3; i++) {
            history.plain(restrictedId, run, Y2019.plusSeconds(i), "Restricted Clerk", "OTHER", "title", null, "R" + i);
        }
        history.plain(standardId, run, Y2019, "Standard Clerk", "STATUS_CHANGE", "status", "New", "Open");

        for (String kind : List.of("all", "native", "imported", "fields", "views")) {
            mvc.perform(get("/cases/" + restricted + "/history?kind=" + kind)).andExpect(status().isNotFound());
        }
        for (String kind : List.of("all", "imported")) {
            assertThat(page("?kind=" + kind))
                    .as("the colleague's readable case counts only its own imported row")
                    .contains("1 event found.", "Standard Clerk")
                    .doesNotContain("Restricted Clerk");
        }
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void pagesOfALongMixedTimelineFollowTheMergedOrder() throws Exception {
        record Row(Instant at, boolean imported, long id, String who) {}
        Random random = new Random(26);
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < 135; i++) {
            Instant at = Y2019.plusSeconds(60L * random.nextInt(40));
            boolean imported = i % 2 == 0;
            String who = (imported ? "Legacy " : "Native ") + String.format(Locale.ROOT, "%03d", i);
            long id = imported
                    ? history.plain(standardId, run, at, who, "OTHER", "title", null, "t")
                    : history.audit(standardId, at, who, "CASE_UPDATED");
            rows.add(new Row(at, imported, id, who));
        }
        rows.sort(Comparator.comparing(Row::at)
                .reversed()
                .thenComparing(Row::imported)
                .thenComparing(Comparator.comparingLong(Row::id).reversed()));

        List<String> shown = new ArrayList<>();
        for (int number = 1; number <= 3; number++) {
            String page = page("?page=" + number);
            assertThat(page).contains("135 events found. Page " + number + " of 3.");
            Matcher who = WHO.matcher(page);
            while (who.find()) {
                shown.add(who.group());
            }
        }
        assertThat(shown).containsExactlyElementsOf(rows.stream().map(Row::who).toList());
        assertThat(rows(page("?page=4"))).isZero();
    }

    /** Two imported and two audit events, alternating in time from 2019 to 2022. */
    private void fourRows() {
        history.plain(standardId, run, Y2019, "Synthetic Legacy Clerk", "STATUS_CHANGE", "status", "New", "In review");
        history.audit(standardId, Instant.parse("2020-01-01T10:00:00Z"), "Synthetic Native Analyst", "CASE_UPDATED");
        history.plain(
                standardId,
                run,
                Instant.parse("2021-01-01T10:00:00Z"),
                null,
                "ASSIGNMENT",
                "owner",
                "Old Team Lead",
                "<b>New</b> Lead");
        history.audit(standardId, Instant.parse("2022-01-01T10:00:00Z"), "Synthetic Native Analyst", "CASE_REASSIGNED");
    }

    private void record(Kind kind, @Nullable String field, @Nullable String before, @Nullable String after) {
        importedHistory.record(
                new ImportedEvent(standardId, Y2019, "Synthetic Legacy Clerk", kind, field, before, after, run));
    }

    private String page(String query) throws Exception {
        return mvc.perform(get("/cases/" + standard + "/history" + query))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private static int count(String page, String text) {
        return page.split(Pattern.quote(text), -1).length - 1;
    }

    private static int rows(String page) {
        return count(page, "<time datetime=");
    }

    private void user(long id, String name) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                id,
                "imported-history-subject-" + id,
                name);
    }
}
