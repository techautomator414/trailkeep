// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
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
 * What the screens show once the evaluator has moved a case (WP-21 part c): two cases whose at-risk and due times are
 * set around a fixed instant are evaluated at it; the case page's chip reads "At risk, 3 days left" and "Overdue 2
 * days" (icon and text), the list's SLA filter finds each under its state only, the History page lists the change
 * under State changes with the states by name, and the owner's Alerts page carries both alerts. The test's one
 * transaction holds everything, the evaluator's chunks included, and rolls back.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class SlaScreensIT {

    static final long OWNER = 990_001L;
    static final long TEAM = 990_100L;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseService cases;

    @Autowired
    private SlaEvaluator evaluator;

    @Autowired
    private EntityManager entityManager;

    /** The fixed evaluation instant: this test's start, to the hour, so the chips' day counts are whole. */
    private Instant now = Instant.EPOCH;

    private CaseNumber atRisk;
    private CaseNumber overdue;

    @BeforeEach
    void seedAndEvaluate() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic SLA Team')", TEAM);
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, 'sla-subject', 'Synthetic Owner', 'owner@example.invalid', FALSE,"
                        + " CURRENT_TIMESTAMP)",
                OWNER);
        CaseSeed seed = new CaseSeed(jdbc);
        seed.member(OWNER, TEAM, TeamRole.ANALYST);
        now = Instant.now().truncatedTo(ChronoUnit.HOURS);
        atRisk = create(seed.caseType(), "Synthetic at-risk case", now.minus(Duration.ofDays(1)), Duration.ofDays(3));
        overdue = create(seed.caseType(), "Synthetic overdue case", now.minus(Duration.ofDays(9)), Duration.ofDays(-2));
        evaluator.evaluate(now);
        entityManager.flush();
        entityManager.clear();
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void theChipsReadAtRiskAndOverdueWithAnIconAndText() throws Exception {
        assertThat(html("/cases/" + atRisk))
                .contains("tk-sla-AT_RISK", "<span aria-hidden=\"true\">▲</span>", "<span>At risk, 3 days left</span>");
        assertThat(html("/cases/" + overdue))
                .contains("tk-sla-OVERDUE", "<span aria-hidden=\"true\">■</span>", "<span>Overdue 2 days</span>");
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void theListFilterFindsEachCaseUnderItsStateOnly() throws Exception {
        String filter = html("/cases?sla=AT_RISK");
        assertThat(filter).contains(atRisk.toString()).doesNotContain(overdue.toString());
        assertThat(html("/cases?sla=OVERDUE")).contains(overdue.toString()).doesNotContain(atRisk.toString());
        assertThat(html("/cases?sla=ON_TRACK")).doesNotContain(atRisk.toString(), overdue.toString());
        for (String state : new String[] {"ON_TRACK", "AT_RISK", "OVERDUE", "MET", "BREACHED", "NOT_APPLICABLE"}) {
            assertThat(filter).as("the drawer offers " + state).contains("value=\"" + state + "\"");
        }
    }

    @Test
    @WithAppUser(id = OWNER, team = TEAM, teamRole = TeamRole.ANALYST)
    void historyAndAlertsTellOfTheChange() throws Exception {
        assertThat(html("/cases/" + atRisk + "/history?kind=state"))
                .contains("SLA state changed by the evaluator", "SLA state: On track → At risk");
        assertThat(html("/cases/" + overdue + "/history?kind=state")).contains("SLA state: On track → Overdue");
        assertThat(html("/alerts"))
                .contains(atRisk + ", &quot;Synthetic at-risk case&quot;, is at risk: it is due on")
                .contains(overdue + ", &quot;Synthetic overdue case&quot;, is overdue: it was due on");
    }

    /** A case through the service, then its SLA row moved onto the fixed timeline. */
    private CaseNumber create(long caseType, String title, Instant atRiskAt, Duration dueFromNow) {
        CaseNumber number = cases.create(
                new Actor(new UserPermissions(OWNER, false, Set.of(), Map.of(TEAM, TeamRole.ANALYST)), "Owner"),
                new CaseFields(
                        caseType,
                        title,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(10),
                        TEAM,
                        OWNER,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        // The SLA row is moved underneath, so the persistence context must not keep the one create wrote.
        entityManager.flush();
        entityManager.clear();
        jdbc.update(
                "UPDATE CASE_SLA SET SLA_STATE = 'ON_TRACK', AT_RISK_AT = ?, SLA_DUE_AT = ? WHERE CASE_ID ="
                        + " (SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?)",
                Timestamp.from(atRiskAt),
                Timestamp.from(now.plus(dueFromNow)),
                number.toString());
        return number;
    }

    private String html(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }
}
