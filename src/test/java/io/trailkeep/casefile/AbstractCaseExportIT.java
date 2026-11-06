// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static io.trailkeep.casefile.FilterBuilder.filter;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseExportService.Prepared;
import io.trailkeep.casefile.CaseExportService.Ready;
import io.trailkeep.casefile.CaseExportService.TooMany;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

/**
 * The CSV export, once per vendor (WP-15 part 4a): restricted and other-team cases are absent from the export exactly
 * as from the list (the colleague's export is equal before and after a restricted case exists, and its row count is
 * the list's count); subject and description are redacted unless a Manager of the team or a Compliance Admin asks
 * for details, and a forced request from anyone else is silently redacted; the export streams across pages in the
 * list's order up to {@code app.export.max-rows} and refuses more; every export is audited. On Oracle the export
 * selects the {@code CLOB} description as it is, which a description over 4000 characters proves.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = "app.export.max-rows=" + AbstractCaseExportIT.MAX_ROWS)
@Transactional
abstract class AbstractCaseExportIT {

    static final int MAX_ROWS = 600;
    private static final String SUBJECT = "Synthetic subject of the export";
    // CaseFields stores line breaks as LF (#74); the export keeps the break inside the quoted cell.
    private static final String DESCRIPTION = "Synthetic description, \"quoted\", with a comma\nand a second line";

    @Autowired
    private CaseExportService exports;

    @Autowired
    private CaseListService list;

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private CaseSeed seed;
    private long team;
    private long analyst;
    private long colleague;
    private long manager;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        analyst = member(TeamRole.ANALYST);
        colleague = member(TeamRole.ANALYST);
        manager = member(TeamRole.MANAGER);
    }

    @Test
    void restrictedCasesAreAbsentFromTheExportExactlyAsFromTheList() throws IOException {
        CaseNumber standard = create(analyst, "Synthetic exported standard", Sensitivity.STANDARD, DESCRIPTION);
        final List<List<String>> before = csv(exports.export(actor(colleague, TeamRole.ANALYST), all(), false));

        CaseNumber restricted = create(analyst, "Synthetic restricted unexported", Sensitivity.RESTRICTED, "Hidden");
        List<List<String>> after = csv(exports.export(actor(colleague, TeamRole.ANALYST), all(), false));

        assertThat(after).isEqualTo(before);
        assertThat(after).hasSize(2);
        assertThat(after.get(1).get(0)).isEqualTo(standard.toString());
        assertThat(after.size() - 1L)
                .isEqualTo(list.list(actor(colleague, TeamRole.ANALYST), all()).total());
        assertThat(csv(exports.export(actor(analyst, TeamRole.ANALYST), all(), false)))
                .extracting(row -> row.get(0))
                .contains(restricted.toString(), standard.toString());
    }

    @Test
    void detailsAreRedactedUnlessAManagerOfTheTeamOrAComplianceAdminAsksForThem() throws IOException {
        create(analyst, "Synthetic detailed case", Sensitivity.STANDARD, DESCRIPTION);
        List<String> header = List.of(
                "Case number",
                "Title",
                "Status",
                "Sensitivity",
                "Priority",
                "Case type",
                "Team",
                "Owner",
                "SLA state",
                "SLA due on",
                "Opened on",
                "Subject / involved parties",
                "Description");

        Ready analysts = ready(exports.export(actor(analyst, TeamRole.ANALYST), all(), true));
        List<List<String>> forced = csv(analysts);
        assertThat(analysts.details()).isFalse();
        assertThat(forced.get(0)).isEqualTo(header);
        assertThat(forced.get(1).subList(11, 13))
                .containsExactly(CaseExportService.REDACTED, CaseExportService.REDACTED);
        assertThat(forced.get(1).subList(1, 9))
                .containsExactly(
                        "Synthetic detailed case",
                        "OPEN",
                        "STANDARD",
                        "NORMAL",
                        "Seed " + seed.caseType(),
                        "Seed Team " + team,
                        "Seed User " + analyst,
                        "ON_TRACK");

        List<List<String>> managers = csv(exports.export(actor(manager, TeamRole.MANAGER), all(), true));
        assertThat(managers.get(1).subList(11, 13)).containsExactly(SUBJECT, DESCRIPTION);
        List<List<String>> unasked = csv(exports.export(actor(manager, TeamRole.MANAGER), all(), false));
        assertThat(unasked.get(1).get(12)).isEqualTo(CaseExportService.REDACTED);
        List<List<String>> admins = csv(exports.export(global(GlobalRole.COMPLIANCE_ADMIN), all(), true));
        assertThat(admins.get(1).subList(11, 13)).containsExactly(SUBJECT, DESCRIPTION);

        assertThat(exports.offersDetails(actor(analyst, TeamRole.ANALYST).permissions()))
                .isFalse();
        assertThat(exports.offersDetails(actor(manager, TeamRole.MANAGER).permissions()))
                .isTrue();
        assertThat(exports.offersDetails(global(GlobalRole.COMPLIANCE_ADMIN).permissions()))
                .isTrue();
        assertThat(exports.offersDetails(global(GlobalRole.SYSTEM_ADMIN).permissions()))
                .isFalse();
    }

    @Test
    void everyExportIsAuditedWithItsRowCountQueryAndWhetherDetailsWereIncluded() throws IOException {
        create(analyst, "Synthetic audited case", Sensitivity.STANDARD, DESCRIPTION);
        CaseListQuery query =
                new CaseListQuery(filter().statuses(CaseStatus.OPEN).mine().build(), CaseSort.TITLE, false, 2);

        csv(exports.export(actor(analyst, TeamRole.ANALYST), query, true));
        csv(exports.export(actor(manager, TeamRole.MANAGER), all(), true));
        entityManager.flush();

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT ACTOR_ID, CASE_ID, ENTITY_TYPE, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE ACTION = 'CASES_EXPORTED' AND ACTOR_ID IN (?, ?) ORDER BY ID",
                analyst,
                manager);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("CASE_ID", null).containsEntry("ENTITY_TYPE", "CASE_LIST");
        assertThat((String) events.get(0).get("NEW_VALUES_JSON"))
                .contains("\"rows\":1", "\"query\":\"status=OPEN&owner=me&sort=title&dir=asc\"", "\"details\":false")
                .doesNotContain(DESCRIPTION, SUBJECT);
        assertThat((String) events.get(1).get("NEW_VALUES_JSON")).contains("\"details\":true");
    }

    @Test
    void anExportStreamsEveryPageInTheListsOrderUpToTheCapAndRefusesMore() throws IOException {
        List<String> seeded = new ArrayList<>();
        for (int i = 0; i < DefaultCaseExportService.PAGE_SIZE + 1; i++) {
            seeded.add(seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN)
                    .toString());
        }
        CaseListQuery byNumber = new CaseListQuery(CaseFilter.none(), CaseSort.NUMBER, false, 7);

        List<List<String>> rows = csv(exports.export(actor(colleague, TeamRole.ANALYST), byNumber, false));
        assertThat(rows.subList(1, rows.size()))
                .extracting(row -> row.get(0))
                .containsExactlyElementsOf(seeded.stream().sorted().toList());

        for (int i = rows.size() - 1; i < MAX_ROWS + 1; i++) {
            seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);
        }
        Prepared refused = exports.export(actor(colleague, TeamRole.ANALYST), byNumber, false);
        assertThat(refused).isEqualTo(new TooMany(MAX_ROWS + 1, MAX_ROWS));
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'CASES_EXPORTED' AND ACTOR_ID = ?",
                        Long.class,
                        colleague))
                .isEqualTo(1);
    }

    @Test
    void onlyTheSelectedCasesTheScopeReadsAreExported() throws IOException {
        CaseNumber picked = create(analyst, "Synthetic picked case", Sensitivity.STANDARD, DESCRIPTION);
        create(analyst, "Synthetic unpicked case", Sensitivity.STANDARD, DESCRIPTION);
        CaseNumber restricted = create(analyst, "Synthetic restricted pick", Sensitivity.RESTRICTED, DESCRIPTION);
        CaseListQuery selected = new CaseListQuery(
                filter().numbers(picked.toString(), restricted.toString(), "CASE-2026-999999")
                        .build(),
                CaseSort.OPENED,
                true,
                1);

        assertThat(csv(exports.export(actor(colleague, TeamRole.ANALYST), selected, false)))
                .extracting(row -> row.get(0))
                .containsExactly("Case number", picked.toString());
        assertThat(csv(exports.export(actor(analyst, TeamRole.ANALYST), selected, false)))
                .extracting(row -> row.get(0))
                .containsExactlyInAnyOrder("Case number", picked.toString(), restricted.toString());
    }

    @Test
    void longDescriptionsAreExportedWhole() throws IOException {
        String longDescription = String.join(" ", Collections.nCopies(200, "Synthetic long description."));
        create(analyst, "Synthetic long case", Sensitivity.STANDARD, longDescription);

        List<List<String>> rows = csv(exports.export(global(GlobalRole.COMPLIANCE_ADMIN), all(), true));

        assertThat(longDescription.length()).isGreaterThan(4000);
        assertThat(rows).extracting(row -> row.get(12)).contains(longDescription);
    }

    private static CaseListQuery all() {
        return new CaseListQuery(CaseFilter.none(), CaseSort.OPENED, true, 1);
    }

    private static Ready ready(Prepared prepared) {
        assertThat(prepared).isInstanceOf(Ready.class);
        return (Ready) prepared;
    }

    private static List<List<String>> csv(Prepared prepared) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ready(prepared).writeTo(out);
        String text = out.toString(StandardCharsets.UTF_8);
        assertThat(text).startsWith("\uFEFF").endsWith("\r\n");
        return parse(text.substring(1));
    }

    /** Reads the export's own dialect back: every cell quoted, quotes doubled, CRLF between rows. */
    private static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                boolean doubled = quoted && i + 1 < text.length() && text.charAt(i + 1) == '"';
                if (doubled) {
                    cell.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (quoted || (c != ',' && c != '\r')) {
                cell.append(c);
            } else {
                row.add(cell.toString());
                cell.setLength(0);
                if (c == '\r') {
                    rows.add(row);
                    row = new ArrayList<>();
                    i++;
                }
            }
        }
        return rows;
    }

    private CaseNumber create(long owner, String title, Sensitivity sensitivity, String description) {
        return cases.create(
                actor(owner, TeamRole.ANALYST),
                new CaseFields(
                        seed.caseType(),
                        title,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC),
                        team,
                        owner,
                        SUBJECT,
                        description,
                        sensitivity));
    }

    private long member(TeamRole role) {
        long user = seed.user();
        seed.member(user, team, role);
        return user;
    }

    private Actor actor(long user, TeamRole role) {
        return new Actor(new UserPermissions(user, false, Set.of(), Map.of(team, role)), "Synthetic Exporter");
    }

    private Actor global(GlobalRole role) {
        return new Actor(new UserPermissions(seed.user(), false, Set.of(role), Map.of()), "Synthetic Global User");
    }
}
