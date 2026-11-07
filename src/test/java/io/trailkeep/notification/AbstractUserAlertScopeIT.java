// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.notification.UserAlertService.Alert;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * Alerts as the person's case scope reads them, once per vendor (issue #101): an alert about a case is listed and
 * counted only while the scope reads the case, so it drops out when the case becomes restricted to others or the
 * person loses the team, and returns with the access; an alert about no case always stays; the page and the unread
 * count agree; another person's alert is never counted. Rows are removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractUserAlertScopeIT {

    private static final Instant AT = Instant.parse("2026-11-07T09:00:00Z");
    private static final long NO_CASE_ALERT = 985_001L;
    private static final long CASE_ALERT = 985_002L;
    private static final long OTHERS_ALERT = 985_003L;

    @Autowired
    private UserAlertService alerts;

    @Autowired
    private CaseScopeService scopes;

    @Autowired
    private JdbcTemplate jdbc;

    private CaseSeed seed;
    private long team;
    private long elsewhere;
    private long owner;
    private long person;
    private long caseId;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        elsewhere = seed.team();
        owner = seed.user();
        person = seed.user();
        seed.member(owner, team, TeamRole.ANALYST);
        seed.member(person, team, TeamRole.ANALYST);
        caseId = seed.caseId(seed.caseRow(team, owner, Sensitivity.STANDARD, CaseStatus.OPEN));
        alert(NO_CASE_ALERT, person, "About nothing", null, 0);
        alert(CASE_ALERT, person, "About the case", caseId, 1);
        alert(OTHERS_ALERT, owner, "Someone else's", caseId, 2);
    }

    @AfterEach
    void removeRows() {
        jdbc.update("DELETE FROM USER_ALERT WHERE ID IN (?, ?, ?)", NO_CASE_ALERT, CASE_ALERT, OTHERS_ALERT);
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID IN (?, ?)", team, elsewhere);
        jdbc.update("DELETE FROM APP_USER WHERE ID IN (?, ?)", owner, person);
        jdbc.update("DELETE FROM TEAM WHERE ID IN (?, ?)", team, elsewhere);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void personWhoReadsTheCaseSeesBothTheirAlertsAndNotSomeoneElses() {
        CaseScope scope = scopeIn(team);

        assertThat(messages(scope)).containsExactly("About the case", "About nothing");
        assertThat(alerts.alerts(scope, person, 1).total()).isEqualTo(2);
        assertThat(alerts.unread(scope, person)).isEqualTo(2);
    }

    @Test
    void alertDropsOutWhenTheCaseBecomesRestrictedAndReturnsWithAccess() {
        jdbc.update("UPDATE CASE_RECORD SET SENSITIVITY = ? WHERE ID = ?", Sensitivity.RESTRICTED.name(), caseId);

        assertThat(messages(scopeIn(team))).containsExactly("About nothing");
        assertThat(alerts.alerts(scopeIn(team), person, 1).total()).isEqualTo(1);
        assertThat(alerts.unread(scopeIn(team), person)).isEqualTo(1);

        jdbc.update("UPDATE CASE_RECORD SET OWNER_ID = ? WHERE ID = ?", person, caseId);
        assertThat(messages(scopeIn(team))).containsExactly("About the case", "About nothing");
        assertThat(alerts.unread(scopeIn(team), person)).isEqualTo(2);
    }

    @Test
    void alertDropsOutWhenThePersonLosesTheTeamButTheCaselessAlertStays() {
        CaseScope scope = scopeIn(elsewhere);

        assertThat(messages(scope)).containsExactly("About nothing");
        assertThat(alerts.alerts(scope, person, 1).total()).isEqualTo(1);
        assertThat(alerts.unread(scope, person)).isEqualTo(1);
    }

    @Test
    void unrestrictedScopeListsEverythingOfThePerson() {
        assertThat(messages(CaseScope.system())).containsExactly("About the case", "About nothing");
        assertThat(alerts.unread(CaseScope.system(), person)).isEqualTo(2);
    }

    private List<String> messages(CaseScope scope) {
        return alerts.alerts(scope, person, 1).alerts().stream()
                .map(Alert::message)
                .toList();
    }

    private CaseScope scopeIn(long teamId) {
        return scopes.forUser(new UserPermissions(person, false, Set.of(), Map.of(teamId, TeamRole.ANALYST)));
    }

    private void alert(long id, long userId, String message, @Nullable Long forCase, int seconds) {
        jdbc.update(
                "INSERT INTO USER_ALERT (ID, USER_ID, MESSAGE, LINK, CASE_ID, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?)",
                id,
                userId,
                message,
                "/cases/x",
                new SqlParameterValue(Types.BIGINT, forCase),
                Timestamp.from(AT.plusSeconds(seconds)));
    }
}
