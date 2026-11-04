// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.security.CaseAction;
import io.trailkeep.security.CaseFacts;
import io.trailkeep.security.CasePermissionService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManagerFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
 * The principal-backed {@link CaseScope} on PostgreSQL agrees with the permission matrix's {@code VIEW} for every
 * kind of person against every kind of case, and loads a case in one statement (WP-11 acceptance).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseScopeMatrixIT {

    @Autowired
    private CaseScopeService scopes;

    @Autowired
    private CasePermissionService permissions;

    @Autowired
    private CaseRecordRepository cases;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private JdbcTemplate jdbc;

    private final Map<CaseNumber, CaseFacts> seeded = new LinkedHashMap<>();
    private final List<UserPermissions> people = new ArrayList<>();
    private long teamA;
    private long analystA;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        teamA = seed.team();
        long teamB = seed.team();
        analystA = seed.user();
        long otherAnalystA = seed.user();
        long managerB = seed.user();
        long analystB = seed.user();
        long movedAnalyst = seed.user();
        long readerA = seed.user();
        Map<Long, List<Long>> owners = Map.of(
                teamA, List.of(analystA, otherAnalystA, movedAnalyst, readerA), teamB, List.of(managerB, analystB));
        owners.forEach((team, ids) -> ids.forEach(owner -> {
            for (Sensitivity sensitivity : Sensitivity.values()) {
                for (CaseStatus status : CaseStatus.values()) {
                    seeded.put(
                            seed.caseRow(team, owner, sensitivity, status),
                            new CaseFacts(team, owner, sensitivity, status));
                }
            }
        }));
        people.add(person(readerA, Set.of(), Map.of(teamA, TeamRole.READER)));
        // Owns team A cases but has since moved to team B: the matrix's NONE rule denies, ownership alone is no grant.
        people.add(person(movedAnalyst, Set.of(), Map.of(teamB, TeamRole.ANALYST)));
        people.add(person(seed.user(), Set.of(GlobalRole.SYSTEM_ADMIN, GlobalRole.COMPLIANCE_ADMIN), Map.of()));
        people.add(person(seed.user(), Set.of(GlobalRole.SYSTEM_ADMIN), Map.of(teamA, TeamRole.MANAGER)));
        people.add(person(analystA, Set.of(), Map.of(teamA, TeamRole.ANALYST)));
        people.add(person(seed.user(), Set.of(), Map.of(teamA, TeamRole.MANAGER)));
        people.add(person(managerB, Set.of(), Map.of(teamA, TeamRole.ANALYST, teamB, TeamRole.MANAGER)));
        people.add(person(analystB, Set.of(), Map.of(teamB, TeamRole.ANALYST)));
        people.add(person(seed.user(), Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()));
        people.add(person(seed.user(), Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of(teamA, TeamRole.READER)));
        people.add(person(seed.user(), Set.of(GlobalRole.SYSTEM_ADMIN), Map.of()));
        people.add(new UserPermissions(otherAnalystA, true, Set.of(), Map.of(teamA, TeamRole.ANALYST)));
        people.add(person(seed.user(), Set.of(), Map.of()));
    }

    @Test
    void scopeAgreesWithTheMatrixForEveryPersonAndCase() {
        int readable = 0;
        for (UserPermissions person : people) {
            CaseScope scope = scopes.forUser(person);
            assertThat(scope.isUnrestricted())
                    .as("principal scopes are never unrestricted")
                    .isFalse();
            for (Map.Entry<CaseNumber, CaseFacts> entry : seeded.entrySet()) {
                boolean expected = permissions.isAllowed(person, entry.getValue(), CaseAction.VIEW);
                String what = person + " reads " + entry.getValue();
                assertThat(scope.canRead(entry.getKey())).as(what).isEqualTo(expected);
                assertThat(cases.findOne(scope, entry.getKey()).isPresent())
                        .as(what)
                        .isEqualTo(expected);
                readable += expected ? 1 : 0;
            }
        }
        assertThat(readable)
                .as("the fixture exercises both answers")
                .isPositive()
                .isLessThan(people.size() * seeded.size());
    }

    @Test
    void absentCasesAreUnreadableEvenForComplianceAdmins() {
        CaseScope admin = scopes.forUser(person(1, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()));
        CaseNumber absent = new CaseNumber("SEED", 2026, 1);

        assertThat(admin.canRead(absent)).isFalse();
        assertThat(cases.findOne(admin, absent)).isEmpty();
    }

    @Test
    void findOneIsOneStatement() {
        UserPermissions analyst = person(analystA, Set.of(), Map.of(teamA, TeamRole.ANALYST));
        CaseScope scope = scopes.forUser(analyst);
        CaseNumber number = seeded.entrySet().stream()
                .filter(entry -> permissions.isAllowed(analyst, entry.getValue(), CaseAction.VIEW))
                .map(Map.Entry::getKey)
                .findFirst()
                .orElseThrow();
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        try {
            assertThat(cases.findOne(scope, number)).isPresent();
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private static UserPermissions person(long id, Set<GlobalRole> global, Map<Long, TeamRole> teams) {
        return new UserPermissions(id, false, global, teams);
    }
}
