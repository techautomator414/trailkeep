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
import io.trailkeep.user.TeamRole;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
 * The dashboard over HTTP (WP-20), with cases in two teams. The analyst's tiles count only what they read: their own
 * open cases, and the at-risk and overdue cases of their team, never a restricted case of someone else in it nor a
 * case of the other team; each tile links to the cases list so filtered. "My cases" lists their open cases by due
 * date, at most ten, with a link to all. The Manager's 21 pending approvals (and one on the other team's case, which
 * they do not read) are 21 on the tile and two pages of "Pending my approval", 20 and 1, each row with Approve, the
 * other list's page kept in the links.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class DashboardControllerIT {

    static final long ANALYST = 991_001L;
    static final long MANAGER = 991_002L;
    static final long COLLEAGUE = 991_003L;
    static final long STRANGER = 991_004L;
    private static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    private CaseSeed seed;
    private long team;
    private long otherTeam;
    private final List<CaseNumber> mine = new ArrayList<>();

    @BeforeEach
    void seedTwoTeams() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        otherTeam = seed.team();
        for (long id : new long[] {ANALYST, MANAGER, COLLEAGUE, STRANGER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, ?, 'user@example.invalid', FALSE, CURRENT_TIMESTAMP)",
                    id,
                    "dashboard-" + id,
                    "Synthetic Person " + id);
        }
        seed.member(ANALYST, team, TeamRole.ANALYST);
        seed.member(MANAGER, team, TeamRole.MANAGER);
        seed.member(COLLEAGUE, team, TeamRole.ANALYST);
        seed.member(STRANGER, otherTeam, TeamRole.ANALYST);
        // The analyst owns 12 open cases, two of them at risk; one more of theirs is closed.
        for (int i = 0; i < 12; i++) {
            mine.add(sla(team, ANALYST, Sensitivity.STANDARD, CaseStatus.OPEN, i < 2 ? "AT_RISK" : "ON_TRACK", i));
        }
        sla(team, ANALYST, Sensitivity.STANDARD, CaseStatus.CLOSED, "MET", 0);
        // A colleague's overdue case they read; a colleague's restricted overdue case and another team's at-risk
        // case they do not.
        sla(team, COLLEAGUE, Sensitivity.STANDARD, CaseStatus.OPEN, "OVERDUE", -3);
        sla(team, COLLEAGUE, Sensitivity.RESTRICTED, CaseStatus.OPEN, "OVERDUE", -3);
        sla(otherTeam, STRANGER, Sensitivity.STANDARD, CaseStatus.OPEN, "AT_RISK", 1);
    }

    @Test
    @WithAppUser(id = ANALYST, team = 0, teamRole = TeamRole.ANALYST)
    void theTilesCountOnlyWhatTheAnalystReadsAndLinkToTheFilteredList() throws Exception {
        String page = html("/");

        assertThat(tile(page, "My open cases")).isEqualTo("12");
        assertThat(tile(page, "At risk")).isEqualTo("2");
        assertThat(tile(page, "Overdue")).as("not the restricted one").isEqualTo("1");
        assertThat(tile(page, "Pending my approval")).isEqualTo("0");
        assertThat(tile(page, "Update requests for me")).isEqualTo("0");
        assertThat(page)
                .contains(
                        "href=\"/cases?status=OPEN&amp;status=PENDING_CLOSURE&amp;owner=me&amp;sort=due&amp;dir=asc\"")
                .contains("href=\"/cases?sla=AT_RISK&amp;sort=due&amp;dir=asc\"")
                .as("an empty list's tile leads to Needs attention, which says so")
                .contains("href=\"#tk-attention\"")
                .doesNotContain("href=\"#tk-approvals\"");
        assertThat(html("/cases?sla=AT_RISK&sort=due&dir=asc"))
                .contains(mine.get(0).toString());
    }

    @Test
    @WithAppUser(id = ANALYST, team = 0, teamRole = TeamRole.ANALYST)
    void myCasesListsTenByDueDateWithALinkToAll() throws Exception {
        String page = html("/");
        String table = page.substring(page.indexOf("aria-labelledby=\"tk-mine\""), page.indexOf("</table>"));

        assertThat(Pattern.compile("<th scope=\"row\">")
                        .matcher(table)
                        .results()
                        .count())
                .isEqualTo(10);
        assertThat(table)
                .contains(mine.get(0).toString(), mine.get(9).toString())
                .doesNotContain(mine.get(10).toString());
        assertThat(table.indexOf(mine.get(0).toString()))
                .isLessThan(table.indexOf(mine.get(9).toString()));
        assertThat(page).contains("See all 12 of my open cases");
    }

    @Test
    @WithAppUser(id = MANAGER, team = 0, teamRole = TeamRole.MANAGER)
    void twentyOneApprovalsAreTwoPagesAndTheOtherTeamsIsNotOne() throws Exception {
        List<CaseNumber> pending = new ArrayList<>();
        for (int i = 0; i < 21; i++) {
            CaseNumber number = sla(team, COLLEAGUE, Sensitivity.STANDARD, CaseStatus.PENDING_CLOSURE, "ON_TRACK", 30);
            closure(number, i);
            pending.add(number);
        }
        closure(sla(otherTeam, STRANGER, Sensitivity.STANDARD, CaseStatus.PENDING_CLOSURE, "ON_TRACK", 30), 99);

        String first = html("/");
        assertThat(tile(first, "Pending my approval")).isEqualTo("21");
        assertThat(first)
                .contains("Page 1 of 2.", "href=\"/?approvals=2&amp;requests=1#tk-approvals\"")
                .contains(pending.get(19).toString())
                .doesNotContain(pending.get(20).toString(), "rel=\"prev\"");
        assertThat(first.split("Approve closure</button>", -1)).hasSize(21);
        String second = html("/?approvals=2");
        assertThat(second)
                .contains(
                        "Page 2 of 2.",
                        pending.get(20).toString(),
                        "href=\"/?approvals=1&amp;requests=1#tk-approvals\"")
                .doesNotContain(pending.get(19).toString(), "rel=\"next\"");
        assertThat(html("/?approvals=9"))
                .as("past the last page shows the last")
                .contains("Page 2 of 2.");
        assertThat(html("/?approvals=x")).as("unreadable is the first page").contains("Page 1 of 2.");
    }

    /** A seeded case and its SLA row, due {@code days} from now. */
    private CaseNumber sla(
            long teamId, long owner, Sensitivity sensitivity, CaseStatus status, String state, int days) {
        CaseNumber number = seed.caseRow(teamId, owner, sensitivity, status);
        Instant due = NOW.plus(Duration.ofDays(days));
        jdbc.update(
                "INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT, SLA_CYCLE) VALUES (?, ?, ?, ?, 1)",
                seed.caseId(number),
                state,
                Timestamp.from(due),
                Timestamp.from(due.minus(Duration.ofDays(5))));
        return number;
    }

    /** A pending closure request on the case, from the colleague to the Manager, in seeding order. */
    private void closure(CaseNumber number, int n) {
        long caseId = seed.caseId(number);
        jdbc.update(
                "INSERT INTO CLOSURE_REQUEST (ID, CASE_ID, REQUESTED_BY, APPROVER_ID, REQUESTED_AT, OUTCOME,"
                        + " RESOLUTION_SUMMARY) VALUES (?, ?, ?, ?, ?, 'Resolved', 'Synthetic summary')",
                caseId * 10,
                caseId,
                COLLEAGUE,
                MANAGER,
                Timestamp.from(NOW.plus(Duration.ofMinutes(n))));
    }

    /** A tile's count, by its label. */
    private static String tile(String page, String label) {
        Matcher tile = Pattern.compile("<span class=\"tk-tile-count\"\\s*>(\\d+)</span> <span>" + label + "</span>")
                .matcher(page);
        int tiles = Math.max(0, page.indexOf("tk-tiles"));
        assertThat(tile.find())
                .as("tile " + label + " in " + page.substring(tiles, Math.min(page.length(), tiles + 1200)))
                .isTrue();
        return tile.group(1);
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
