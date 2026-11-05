// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cases list on PostgreSQL (WP-15 part 1a): restricted and other-team cases are absent from rows and counts
 * exactly as if they did not exist; archived cases are hidden; every allow-listed sort with stable paging; and a page
 * read in four statements.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseListServiceIT {

    @Autowired
    private CaseListService list;

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    private CaseSeed seed;
    private long team;
    private long otherTeam;
    private long analyst;
    private long colleague;
    private long reader;
    private long manager;
    private long otherManager;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        otherTeam = seed.team();
        analyst = member(team, TeamRole.ANALYST);
        colleague = member(team, TeamRole.ANALYST);
        reader = member(team, TeamRole.READER);
        manager = member(team, TeamRole.MANAGER);
        otherManager = member(otherTeam, TeamRole.MANAGER);
    }

    @Test
    void restrictedAndOtherTeamCasesAreAbsentFromRowsCountsAndChoicesExactlyLikeAbsentOnes() {
        CaseNumber standard = create(analyst, team, "Synthetic standard listed", Sensitivity.STANDARD);
        CaseNumber restricted = create(analyst, team, "Synthetic restricted unlisted", Sensitivity.RESTRICTED);
        create(otherManager, otherTeam, "Synthetic other team unlisted", Sensitivity.STANDARD);

        for (long outsider : List.of(colleague, reader)) {
            CaseList page = all(outsider);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.pages()).isEqualTo(1);
            assertThat(numbers(page)).containsExactly(standard);
        }
        assertThat(numbers(all(analyst))).containsExactlyInAnyOrder(standard, restricted);
        assertThat(numbers(all(manager))).containsExactlyInAnyOrder(standard, restricted);
        assertThat(all(otherManager).rows())
                .extracting(CaseList.Row::title)
                .containsExactly("Synthetic other team unlisted");

        CaseList beforeDeletion = all(colleague);
        delete(restricted);
        assertThat(all(colleague)).isEqualTo(beforeDeletion);
    }

    @Test
    void archivedCasesAreHiddenFromRowsAndCountUntilFiltersCanAskForThem() {
        CaseNumber open = create(analyst, team, "Synthetic open", Sensitivity.STANDARD);
        CaseNumber closed = create(analyst, team, "Synthetic closed", Sensitivity.STANDARD);
        CaseNumber archived = create(analyst, team, "Synthetic archived", Sensitivity.STANDARD);
        status(closed, CaseStatus.CLOSED);
        status(archived, CaseStatus.ARCHIVED);

        CaseList page = all(manager);
        assertThat(numbers(page)).containsExactlyInAnyOrder(open, closed);
        assertThat(page.total()).isEqualTo(2);
    }

    @Test
    void everyAllowListedColumnSortsBothWaysAndCasesWithoutAnSlaComeLast() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        CaseNumber bravo = create(fields(analyst, team, "bravo", CasePriority.NORMAL, today.minusDays(10)));
        CaseNumber alpha = create(fields(analyst, team, "Alpha", CasePriority.NORMAL, today.minusDays(5)));
        CaseNumber urgent = create(fields(analyst, team, "charlie", CasePriority.HIGH, today));
        CaseNumber noSla = seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);

        assertThat(sorted(CaseSort.TITLE, false)).containsExactly(alpha, bravo, urgent, noSla);
        assertThat(sorted(CaseSort.TITLE, true)).containsExactly(noSla, urgent, bravo, alpha);
        assertThat(sorted(CaseSort.PRIORITY, false).get(0)).isEqualTo(urgent);
        assertThat(sorted(CaseSort.PRIORITY, true).get(3)).isEqualTo(urgent);
        assertThat(sorted(CaseSort.DUE, false)).containsExactly(bravo, alpha, urgent, noSla);
        assertThat(sorted(CaseSort.DUE, true)).containsExactly(urgent, alpha, bravo, noSla);
        assertThat(sorted(CaseSort.NUMBER, false))
                .isSortedAccordingTo((a, b) -> a.toString().compareTo(b.toString()));
        assertThat(sorted(CaseSort.OPENED, true)).containsExactlyInAnyOrder(alpha, bravo, urgent, noSla);
    }

    @Test
    void pagesNeverOverlapAndAPagePastTheLastShowsTheLast() {
        List<CaseNumber> seeded = new ArrayList<>();
        for (int i = 0; i <= CaseListService.PAGE_SIZE; i++) {
            seeded.add(seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN));
        }

        CaseList first = list.list(actor(reader, TeamRole.READER), new CaseListQuery(CaseSort.OPENED, true, 1));
        CaseList last = list.list(actor(reader, TeamRole.READER), new CaseListQuery(CaseSort.OPENED, true, 99));

        assertThat(first.total()).isEqualTo(CaseListService.PAGE_SIZE + 1);
        assertThat(first.pages()).isEqualTo(2);
        assertThat(first.rows()).hasSize(CaseListService.PAGE_SIZE);
        assertThat(last.page()).isEqualTo(2);
        List<CaseNumber> both = new ArrayList<>(numbers(first));
        both.addAll(numbers(last));
        assertThat(both).containsExactlyInAnyOrderElementsOf(seeded);
    }

    @Test
    void pagesAreReadInFourStatementsWhateverTheyShow() {
        for (int i = 0; i < 5; i++) {
            create(i % 2 == 0 ? analyst : manager, team, "Synthetic counted " + i, Sensitivity.STANDARD);
        }
        entityManager.flush();
        entityManager.clear();
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        CaseList page;
        try {
            page = all(manager);
            // Count, rows, team names, owner names.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
        assertThat(page.rows()).hasSize(5).allSatisfy(row -> {
            assertThat(row.teamName()).isEqualTo("Seed Team " + team);
            assertThat(row.ownerName()).startsWith("Seed User ");
            assertThat(row.sla().state()).isEqualTo(SlaState.ON_TRACK);
        });
    }

    private List<CaseNumber> sorted(CaseSort sort, boolean descending) {
        return numbers(list.list(actor(analyst, TeamRole.ANALYST), new CaseListQuery(sort, descending, 1)));
    }

    private CaseList all(long user) {
        return list.list(actor(user, roleOf(user)), CaseListQuery.parse(Map.of()));
    }

    private static List<CaseNumber> numbers(CaseList page) {
        return page.rows().stream().map(CaseList.Row::number).toList();
    }

    private TeamRole roleOf(long user) {
        if (user == reader) {
            return TeamRole.READER;
        }
        return user == manager || user == otherManager ? TeamRole.MANAGER : TeamRole.ANALYST;
    }

    private Actor actor(long user, TeamRole role) {
        long in = user == otherManager ? otherTeam : team;
        return new Actor(new UserPermissions(user, false, Set.of(), Map.of(in, role)), "Synthetic List User");
    }

    private CaseNumber create(long owner, long inTeam, String title, Sensitivity sensitivity) {
        CaseFields fields = fields(owner, inTeam, title, CasePriority.NORMAL, LocalDate.now(ZoneOffset.UTC));
        return create(new CaseFields(
                fields.caseTypeId(),
                title,
                CasePriority.NORMAL,
                fields.identifiedOn(),
                inTeam,
                owner,
                fields.subjectText(),
                fields.description(),
                sensitivity));
    }

    private CaseNumber create(CaseFields fields) {
        return cases.create(actor(fields.ownerId(), roleOf(fields.ownerId())), fields);
    }

    private CaseFields fields(long owner, long inTeam, String title, CasePriority priority, LocalDate identifiedOn) {
        return new CaseFields(
                seed.caseType(),
                title,
                priority,
                identifiedOn,
                inTeam,
                owner,
                "Synthetic subject never listed",
                "Synthetic description",
                Sensitivity.STANDARD);
    }

    private long member(long inTeam, TeamRole role) {
        long user = seed.user();
        seed.member(user, inTeam, role);
        return user;
    }

    private void status(CaseNumber number, CaseStatus status) {
        entityManager.flush();
        jdbc.update("UPDATE CASE_RECORD SET STATUS = ? WHERE CASE_NUMBER = ?", status.name(), number.toString());
    }

    private void delete(CaseNumber number) {
        long id = seed.caseId(number);
        entityManager.flush();
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE CASE_ID = ?", id);
        jdbc.update("DELETE FROM CASE_SEARCH WHERE CASE_ID = ?", id);
        jdbc.update("DELETE FROM CASE_SLA WHERE CASE_ID = ?", id);
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", id);
    }
}
