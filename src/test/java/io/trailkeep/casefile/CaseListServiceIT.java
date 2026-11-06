// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static io.trailkeep.casefile.FilterBuilder.filter;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.Choice;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * The cases list on PostgreSQL (WP-15 part 1): restricted and other-team cases are absent from rows, counts and the
 * filters' choices exactly as if they did not exist; the basic filters; SLA state, priority, the identified range
 * with both ends included, and sensitivity, which is offered only to people who read others' restricted cases and
 * never widens what the scope reads; every allow-listed sort with stable paging; and a page read in six statements.
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
            assertThat(page.teams()).extracting(Choice::id).containsExactly(team);
            assertThat(page.owners()).extracting(Choice::id).containsExactly(analyst);
            // "restricted" appears only in the restricted case's title: no row and a count of 0.
            CaseList searched = search(outsider, "restricted");
            assertThat(searched.rows()).isEmpty();
            assertThat(searched.total()).isZero();
            assertThat(search(outsider, "unlisted").total()).isZero();
        }
        assertThat(numbers(all(analyst))).containsExactlyInAnyOrder(standard, restricted);
        assertThat(numbers(search(analyst, "restricted"))).containsExactly(restricted);
        assertThat(search(analyst, "restricted").total()).isEqualTo(1);
        assertThat(numbers(all(manager))).containsExactlyInAnyOrder(standard, restricted);
        assertThat(search(manager, "unlisted").total()).isEqualTo(1);
        assertThat(all(otherManager).rows())
                .extracting(CaseList.Row::title)
                .containsExactly("Synthetic other team unlisted");

        CaseList beforeDeletion = all(colleague);
        CaseList searchedBefore = search(colleague, "Synthetic");
        delete(restricted);
        assertThat(all(colleague)).isEqualTo(beforeDeletion);
        assertThat(search(colleague, "Synthetic")).isEqualTo(searchedBefore);
    }

    @Test
    void basicFiltersNarrowAndArchivedCasesAppearOnlyWhenAskedFor() {
        final long otherType = new CaseSeed(jdbc).caseType();
        final CaseNumber mine = create(analyst, team, "Synthetic mine", Sensitivity.STANDARD);
        final CaseNumber managers = create(manager, team, "Synthetic manager's", Sensitivity.STANDARD);
        CaseNumber closed = create(analyst, team, "Synthetic closed", Sensitivity.STANDARD);
        CaseNumber archived = create(analyst, team, "Synthetic archived", Sensitivity.STANDARD);
        status(closed, CaseStatus.CLOSED);
        status(archived, CaseStatus.ARCHIVED);
        entityManager.flush();
        jdbc.update("UPDATE CASE_RECORD SET CASE_TYPE_ID = ? WHERE CASE_NUMBER = ?", otherType, managers.toString());

        assertThat(numbers(all(manager))).containsExactlyInAnyOrder(mine, managers, closed);
        assertThat(numbers(filtered(manager, Set.of(CaseStatus.ARCHIVED), null, null, false)))
                .containsExactly(archived);
        assertThat(numbers(filtered(manager, Set.of(CaseStatus.OPEN, CaseStatus.CLOSED), null, null, false)))
                .containsExactlyInAnyOrder(mine, managers, closed);
        assertThat(numbers(filtered(manager, Set.of(), otherType, null, false))).containsExactly(managers);
        assertThat(numbers(filtered(manager, Set.of(), null, manager, false))).containsExactly(managers);
        assertThat(numbers(filtered(analyst, Set.of(), null, manager, true))).containsExactlyInAnyOrder(mine, closed);
        assertThat(numbers(list.list(
                        actor(manager, TeamRole.MANAGER),
                        query(filter().team(otherTeam).build(), CaseSort.OPENED, true, 1))))
                .isEmpty();
    }

    @Test
    void slaStatePriorityAndTheIdentifiedRangeNarrowWithBothEndsOfTheRangeIncluded() {
        // IDENTIFIED_ON is a calendar date, entered as a day in app.timezone, so the range compares days, inclusive.
        LocalDate day = LocalDate.now(ZoneOffset.UTC).minusDays(20);
        final CaseNumber before =
                create(fields(analyst, team, "Synthetic before", CasePriority.NORMAL, day.minusDays(1)));
        final CaseNumber first = create(fields(analyst, team, "Synthetic first", CasePriority.HIGH, day));
        final CaseNumber last = create(fields(analyst, team, "Synthetic last", CasePriority.NORMAL, day.plusDays(1)));
        final CaseNumber after = create(fields(analyst, team, "Synthetic after", CasePriority.HIGH, day.plusDays(2)));
        sla(before, SlaState.ON_TRACK);
        sla(first, SlaState.OVERDUE);
        sla(last, SlaState.ON_TRACK);
        sla(after, SlaState.AT_RISK);

        assertThat(numbers(listed(analyst, filter().identified(day, day.plusDays(1)))))
                .containsExactlyInAnyOrder(first, last);
        assertThat(numbers(listed(analyst, filter().identified(day, day)))).containsExactly(first);
        assertThat(numbers(listed(analyst, filter().identified(day.plusDays(1), null))))
                .containsExactlyInAnyOrder(last, after);
        assertThat(numbers(listed(analyst, filter().identified(null, day.minusDays(1)))))
                .containsExactly(before);
        assertThat(numbers(listed(analyst, filter().sla(SlaState.OVERDUE, SlaState.AT_RISK))))
                .containsExactlyInAnyOrder(first, after);
        assertThat(numbers(listed(analyst, filter().sla(SlaState.ON_TRACK)))).containsExactlyInAnyOrder(before, last);
        assertThat(listed(analyst, filter().sla(SlaState.BREACHED)).total()).isZero();
        assertThat(numbers(listed(analyst, filter().priority(CasePriority.HIGH))))
                .containsExactlyInAnyOrder(first, after);
        CaseList all = listed(
                analyst,
                filter().priority(CasePriority.HIGH).sla(SlaState.OVERDUE).identified(day, day));
        assertThat(all.total()).isEqualTo(1);
        assertThat(numbers(all)).containsExactly(first);
    }

    @Test
    void sensitivityIsOfferedOnlyToThoseWhoReadOthersRestrictedCasesAndNeverWidensTheScope() {
        final CaseNumber standard = create(analyst, team, "Synthetic standard", Sensitivity.STANDARD);
        final CaseNumber restricted = create(analyst, team, "Synthetic restricted", Sensitivity.RESTRICTED);

        for (long outsider : List.of(colleague, reader)) {
            CaseList asked = listed(outsider, filter().sensitivity(Sensitivity.RESTRICTED));
            assertThat(asked.rows()).isEmpty();
            assertThat(asked.total()).isZero();
            assertThat(asked.offersSensitivity()).isFalse();
            assertThat(numbers(listed(outsider, filter().sensitivity(Sensitivity.STANDARD))))
                    .containsExactly(standard);
        }
        // The owner reads their own restricted case, so the filter finds it, but reads no one else's: not offered.
        CaseList owners = listed(analyst, filter().sensitivity(Sensitivity.RESTRICTED));
        assertThat(numbers(owners)).containsExactly(restricted);
        assertThat(owners.offersSensitivity()).isFalse();
        CaseList managers = listed(manager, filter().sensitivity(Sensitivity.RESTRICTED));
        assertThat(numbers(managers)).containsExactly(restricted);
        assertThat(managers.offersSensitivity()).isTrue();
        assertThat(listed(otherManager, filter().sensitivity(Sensitivity.RESTRICTED))
                        .total())
                .isZero();
        assertThat(list.list(global(GlobalRole.COMPLIANCE_ADMIN), query(CaseFilter.none(), CaseSort.OPENED, true, 1))
                        .offersSensitivity())
                .isTrue();
        CaseList systemAdmins = list.list(
                global(GlobalRole.SYSTEM_ADMIN),
                query(filter().sensitivity(Sensitivity.RESTRICTED).build(), CaseSort.OPENED, true, 1));
        assertThat(systemAdmins.offersSensitivity()).isFalse();
        assertThat(systemAdmins.total()).isZero();
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

        CaseList first = list.list(actor(reader, TeamRole.READER), query(CaseFilter.none(), CaseSort.OPENED, true, 1));
        CaseList last = list.list(actor(reader, TeamRole.READER), query(CaseFilter.none(), CaseSort.OPENED, true, 99));

        assertThat(first.total()).isEqualTo(CaseListService.PAGE_SIZE + 1);
        assertThat(first.pages()).isEqualTo(2);
        assertThat(first.rows()).hasSize(CaseListService.PAGE_SIZE);
        assertThat(last.page()).isEqualTo(2);
        List<CaseNumber> both = new ArrayList<>(numbers(first));
        both.addAll(numbers(last));
        assertThat(both).containsExactlyInAnyOrderElementsOf(seeded);
    }

    @Test
    void pagesAreReadInSixStatementsWhateverTheyShow() {
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
            page = listed(
                    manager,
                    filter().text("counted")
                            .sla(SlaState.ON_TRACK, SlaState.AT_RISK)
                            .priority(CasePriority.NORMAL)
                            .sensitivity(Sensitivity.STANDARD)
                            .identified(LocalDate.now(ZoneOffset.UTC).minusDays(1), null));
            // Count, rows, team and owner pairs, team names, owner names, case types. The SLA states are a
            // subquery inside the count and the rows, and whether to offer sensitivity is a matrix decision.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(6);
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
        return numbers(list.list(actor(analyst, TeamRole.ANALYST), query(CaseFilter.none(), sort, descending, 1)));
    }

    private CaseList all(long user) {
        return filtered(user, Set.of(), null, null, false);
    }

    private CaseList search(long user, String text) {
        return list.list(actor(user, roleOf(user)), query(filter().text(text).build(), CaseSort.OPENED, true, 1));
    }

    private CaseList listed(long user, FilterBuilder filter) {
        return list.list(actor(user, roleOf(user)), query(filter.build(), CaseSort.OPENED, true, 1));
    }

    private CaseList filtered(
            long user, Set<CaseStatus> statuses, @Nullable Long type, @Nullable Long owner, boolean mine) {
        FilterBuilder filter = filter().statuses(statuses.toArray(CaseStatus[]::new))
                .type(type)
                .owner(owner);
        return listed(user, mine ? filter.mine() : filter);
    }

    private static CaseListQuery query(CaseFilter filter, CaseSort sort, boolean descending, int page) {
        return new CaseListQuery(filter, sort, descending, page);
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

    private Actor global(GlobalRole role) {
        return new Actor(new UserPermissions(seed.user(), false, Set.of(role), Map.of()), "Synthetic Global User");
    }

    private void sla(CaseNumber number, SlaState state) {
        entityManager.flush();
        jdbc.update("UPDATE CASE_SLA SET SLA_STATE = ? WHERE CASE_ID = ?", state.name(), seed.caseId(number));
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
