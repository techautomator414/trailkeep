// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import static io.trailkeep.testsupport.ReportFixture.ACCESS;
import static io.trailkeep.testsupport.ReportFixture.ALPHA;
import static io.trailkeep.testsupport.ReportFixture.ALPHA_NAME;
import static io.trailkeep.testsupport.ReportFixture.ANALYST;
import static io.trailkeep.testsupport.ReportFixture.BETA;
import static io.trailkeep.testsupport.ReportFixture.BETA_ANALYST;
import static io.trailkeep.testsupport.ReportFixture.BETA_NAME;
import static io.trailkeep.testsupport.ReportFixture.HANDLING;
import static io.trailkeep.testsupport.ReportFixture.MANAGER;
import static io.trailkeep.testsupport.ReportFixture.OTHER_OWNER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseReportService.AgingRow;
import io.trailkeep.casefile.CaseReportService.ComplianceRow;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.report.ReportExportService.Csv;
import io.trailkeep.report.ReportService.AgingLine;
import io.trailkeep.report.ReportService.AgingReport;
import io.trailkeep.report.ReportService.ComplianceLine;
import io.trailkeep.report.ReportService.ComplianceReport;
import io.trailkeep.report.ReportService.OverdueLine;
import io.trailkeep.report.ReportService.WaitingLine;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.shared.csv.CsvFormat;
import io.trailkeep.testsupport.ReportFixture;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The aging and SLA-compliance reports, once per vendor (WP-22 part a), against {@link ReportFixture}'s hand-computed
 * cases, cell by cell: a Manager counts their team's cases, restricted ones included; an Analyst of the same team does
 * not count the restricted case they do not own; a Compliance Admin counts every team; the totals and the share
 * within the SLA (rounded half up) follow; the age buckets and the period are exact at their edges, archived cases and
 * closures outside the period are not counted. A person with neither a team role nor Compliance Admin is refused,
 * and a period that does not parse, runs backwards or is over 366 days is invalid. The CSV (part b) holds the same
 * cells, header first and the total row last, with formula-like names defused; every export is audited, a refused or
 * invalid one is not.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractReportIT {

    private static final AgingLine ANALYST_AGING =
            new AgingLine(ALPHA_NAME, "Synthetic Report Analyst", new AgingRow(ALPHA, ANALYST, 2, 2, 0, 0, 1, 2, 1, 0));
    private static final AgingLine MANAGER_AGING =
            new AgingLine(ALPHA_NAME, "Synthetic Report Manager", new AgingRow(ALPHA, MANAGER, 0, 0, 2, 1, 1, 0, 1, 1));
    private static final AgingLine RESTRICTED_AGING = new AgingLine(
            ALPHA_NAME, "Synthetic Restricted Owner", new AgingRow(ALPHA, OTHER_OWNER, 0, 1, 0, 0, 0, 0, 1, 0));
    private static final ComplianceRow ALPHA_ACCESS = new ComplianceRow(ALPHA, ACCESS, 1, 1, 1, 3);
    private static final ComplianceRow ALPHA_HANDLING = new ComplianceRow(ALPHA, HANDLING, 1, 0, 1, 0);

    @Autowired
    private ReportService reports;

    @Autowired
    private ReportExportService exports;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private InstantSource clock;

    @Autowired
    private AppProperties app;

    private ReportFixture fixture;
    private LocalDate today;

    @BeforeEach
    void seed() {
        today = LocalDate.ofInstant(clock.instant(), app.timezone());
        fixture = new ReportFixture(jdbc, today, app.timezone());
        fixture.seed();
    }

    @Test
    void managersCountTheirTeamIncludingRestrictedCasesCellByCell() {
        Actor manager = team(ReportFixture.MANAGER, ALPHA, TeamRole.MANAGER);

        AgingReport aging = reports.aging(manager);
        assertThat(aging.asOf()).isEqualTo(today);
        assertThat(aging.lines()).containsExactly(ANALYST_AGING, MANAGER_AGING, RESTRICTED_AGING);
        assertThat(aging.total()).isEqualTo(new AgingRow(0, 0, 2, 3, 2, 1, 2, 2, 3, 1));
        assertThat(aging.overdue())
                .containsExactly(
                        overdue(5, "Synthetic Report Manager", 61),
                        overdue(6, "Synthetic Restricted Owner", 5),
                        overdue(2, "Synthetic Report Analyst", 1));
        assertThat(aging.waiting())
                .containsExactly(
                        waiting(6, "Synthetic Restricted Owner", 8), waiting(0, "Synthetic Report Analyst", 3));

        ComplianceReport compliance = compliance(manager);
        assertThat(compliance.lines())
                .containsExactly(
                        new ComplianceLine(ALPHA_NAME, ALPHA_ACCESS, 50),
                        new ComplianceLine(ALPHA_NAME, ALPHA_HANDLING, 100));
        assertThat(compliance.total()).isEqualTo(new ComplianceRow(0, "", 2, 1, 2, 3));
        assertThat(compliance.withinSla()).as("2 of 3, rounded half up").isEqualTo(67);
        assertThat(compliance.from()).isEqualTo(fixture.from());
        assertThat(compliance.to()).isEqualTo(fixture.to());
    }

    @Test
    void anAnalystDoesNotCountTheRestrictedCaseTheyDoNotOwn() {
        Actor analyst = team(ReportFixture.ANALYST, ALPHA, TeamRole.ANALYST);

        AgingReport aging = reports.aging(analyst);
        assertThat(aging.lines()).containsExactly(ANALYST_AGING, MANAGER_AGING);
        assertThat(aging.overdue())
                .containsExactly(overdue(5, "Synthetic Report Manager", 61), overdue(2, "Synthetic Report Analyst", 1));
        assertThat(aging.waiting()).containsExactly(waiting(0, "Synthetic Report Analyst", 3));
        assertThat(compliance(analyst).lines())
                .containsExactly(
                        new ComplianceLine(ALPHA_NAME, new ComplianceRow(ALPHA, ACCESS, 1, 1, 1, 2), 50),
                        new ComplianceLine(ALPHA_NAME, ALPHA_HANDLING, 100));
    }

    @Test
    void complianceAdminsCountEveryTeamAndReadersTheirOwn() {
        Actor admin = global(GlobalRole.COMPLIANCE_ADMIN);
        AgingLine betaAging = new AgingLine(
                BETA_NAME, "Synthetic Beta Analyst", new AgingRow(BETA, BETA_ANALYST, 1, 1, 0, 1, 1, 1, 1, 0));
        ComplianceLine betaCompliance = new ComplianceLine(BETA_NAME, new ComplianceRow(BETA, ACCESS, 1, 1, 1, 1), 50);

        assertThat(reports.aging(admin).lines())
                .filteredOn(
                        line -> line.counts().teamId() == ALPHA || line.counts().teamId() == BETA)
                .containsExactly(ANALYST_AGING, MANAGER_AGING, RESTRICTED_AGING, betaAging);
        assertThat(compliance(admin).lines())
                .filteredOn(
                        line -> line.counts().teamId() == ALPHA || line.counts().teamId() == BETA)
                .containsExactly(
                        new ComplianceLine(ALPHA_NAME, ALPHA_ACCESS, 50),
                        new ComplianceLine(ALPHA_NAME, ALPHA_HANDLING, 100),
                        betaCompliance);

        AgingReport all = reports.aging(admin);
        assertThat(all.overdue()).contains(overdue(17, "Synthetic Beta Analyst", 15));
        assertThat(all.waiting()).contains(waiting(14, "Synthetic Beta Analyst", 1));

        Actor reader = team(ReportFixture.BETA_ANALYST, BETA, TeamRole.READER);
        AgingReport beta = reports.aging(reader);
        assertThat(beta.lines()).containsExactly(betaAging);
        assertThat(beta.overdue()).containsExactly(overdue(17, "Synthetic Beta Analyst", 15));
        assertThat(beta.waiting()).containsExactly(waiting(14, "Synthetic Beta Analyst", 1));
        assertThat(compliance(reader).lines()).containsExactly(betaCompliance);
    }

    @Test
    void teamsWithoutCasesHaveNoLinesAndNoShare() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", 984_013L, "Empty Reporting Team");
        Actor manager = team(ReportFixture.MANAGER, 984_013L, TeamRole.MANAGER);

        AgingReport aging = reports.aging(manager);
        assertThat(aging.lines()).isEmpty();
        assertThat(aging.total()).isEqualTo(new AgingRow(0, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertThat(aging.overdue()).isEmpty();
        assertThat(aging.waiting()).isEmpty();
        ComplianceReport compliance = compliance(manager);
        assertThat(compliance.lines()).isEmpty();
        assertThat(compliance.withinSla()).isNull();
    }

    @Test
    void theDefaultPeriodIsTheLastThirtyDaysAndAPeriodIsAtMost366Days() {
        Actor manager = team(ReportFixture.MANAGER, ALPHA, TeamRole.MANAGER);

        ComplianceReport defaults = reports.compliance(manager, null, " ");
        assertThat(defaults.to()).isEqualTo(today);
        assertThat(defaults.from()).isEqualTo(today.minusDays(29));
        LocalDate to = fixture.to();
        assertThat(reports.compliance(manager, to.minusDays(365).toString(), to.toString())
                        .from())
                .isEqualTo(to.minusDays(365));
        assertThat(reports.compliance(manager, to.toString(), to.toString()).to())
                .isEqualTo(to);

        assertKind(() -> reports.compliance(manager, to.minusDays(366).toString(), to.toString()), Kind.INVALID);
        assertKind(
                () -> reports.compliance(manager, to.toString(), to.minusDays(1).toString()), Kind.INVALID);
        assertKind(() -> reports.compliance(manager, "2026-13-01", null), Kind.INVALID);
        assertKind(() -> reports.compliance(manager, null, "yesterday"), Kind.INVALID);
    }

    @Test
    void someoneWithNeitherATeamRoleNorComplianceAdminIsRefusedBeforeThePeriodIsRead() {
        Actor systemAdmin = global(GlobalRole.SYSTEM_ADMIN);

        assertKind(() -> reports.aging(systemAdmin), Kind.FORBIDDEN);
        assertKind(() -> reports.compliance(systemAdmin, "not a date", null), Kind.FORBIDDEN);
    }

    @Test
    void theAgingCsvHoldsTheSameCellsThenTheListsAsSections() {
        String header = "\"Team\",\"Owner\",\"0-30 days\",\"31-60 days\",\"61-90 days\",\"Over 90 days\",\"On track\","
                + "\"At risk\",\"Overdue\",\"No SLA\"\r\n";

        assertThat(exports.aging(team(ReportFixture.MANAGER, ALPHA, TeamRole.MANAGER))
                        .content())
                .isEqualTo(CsvFormat.BOM
                        + header
                        + csv(ALPHA_NAME, "Synthetic Report Analyst", "2", "2", "0", "0", "1", "2", "1", "0")
                        + csv(ALPHA_NAME, "Synthetic Report Manager", "0", "0", "2", "1", "1", "0", "1", "1")
                        + csv(ALPHA_NAME, "Synthetic Restricted Owner", "0", "1", "0", "0", "0", "0", "1", "0")
                        + csv("Total", "", "2", "3", "2", "1", "2", "2", "3", "1")
                        + "\r\n"
                        + csv("Overdue case", "Title", "Owner", "Due", "Days overdue")
                        + csv(caseOf(5), titleOf(5), "Synthetic Report Manager", day(61), "61")
                        + csv(caseOf(6), titleOf(6), "Synthetic Restricted Owner", day(5), "5")
                        + csv(caseOf(2), titleOf(2), "Synthetic Report Analyst", day(1), "1")
                        + "\r\n"
                        + csv("Unanswered request on", "Title", "Asked of", "Asked by", "Requested on", "Days waiting")
                        + csv(
                                caseOf(6),
                                titleOf(6),
                                "Synthetic Restricted Owner",
                                "Synthetic Report Manager",
                                day(8),
                                "8")
                        + csv(
                                caseOf(0),
                                titleOf(0),
                                "Synthetic Report Analyst",
                                "Synthetic Report Manager",
                                day(3),
                                "3"));
        assertThat(exports.aging(team(ReportFixture.ANALYST, ALPHA, TeamRole.ANALYST))
                        .content())
                .doesNotContain(caseOf(6), ReportFixture.MESSAGE);
    }

    @Test
    void theCsvHoldsTheSameCellsAndEveryExportIsAudited() {
        Actor manager = team(ReportFixture.MANAGER, ALPHA, TeamRole.MANAGER);

        Csv aging = exports.aging(manager);
        assertThat(aging.fileName()).isEqualTo("aging-" + today + ".csv");

        Csv compliance = exports.compliance(
                manager, fixture.from().toString(), fixture.to().toString());
        String period = fixture.from() + "-to-" + fixture.to();
        assertThat(compliance.fileName()).isEqualTo("sla-compliance-" + period + ".csv");
        assertThat(compliance.content())
                .isEqualTo(CsvFormat.BOM
                        + csv(
                                "Team",
                                "Case type",
                                "Closed within SLA",
                                "Closed late",
                                "At risk",
                                "Overdue",
                                "Share within SLA (%)")
                        + csv(ALPHA_NAME, ACCESS, "1", "1", "1", "3", "50")
                        + csv(ALPHA_NAME, HANDLING, "1", "0", "1", "0", "100")
                        + csv("Total", "", "2", "1", "2", "3", "67"));

        entityManager.flush();
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT CASE_ID, ENTITY_TYPE, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE ACTION = 'REPORT_EXPORTED' AND ACTOR_ID = ? ORDER BY ID",
                ReportFixture.MANAGER);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsEntry("CASE_ID", null).containsEntry("ENTITY_TYPE", "REPORT");
        assertThat((String) events.get(0).get("NEW_VALUES_JSON"))
                .contains("\"report\":\"aging\"", "\"period\":\"" + today + "\"", "\"lines\":3");
        assertThat((String) events.get(1).get("NEW_VALUES_JSON"))
                .contains("\"report\":\"compliance\"", "\"period\":\"" + period + "\"", "\"lines\":2");
    }

    @Test
    void namesThatASpreadsheetWouldRunAreDefusedAndAnEmptyShareIsAnEmptyCell() {
        jdbc.update("UPDATE TEAM SET NAME = '=HYPERLINK(\"http://example.invalid\")' WHERE ID = ?", ALPHA);
        jdbc.update("UPDATE APP_USER SET DISPLAY_NAME = '+Synthetic Plus' WHERE ID = ?", ReportFixture.ANALYST);
        jdbc.update("UPDATE APP_USER SET DISPLAY_NAME = '-Synthetic Minus' WHERE ID = ?", ReportFixture.MANAGER);
        jdbc.update("UPDATE APP_USER SET DISPLAY_NAME = '@Synthetic At' WHERE ID = ?", ReportFixture.OTHER_OWNER);
        Actor manager = team(ReportFixture.MANAGER, ALPHA, TeamRole.MANAGER);

        assertThat(exports.aging(manager).content())
                .contains("\"'=HYPERLINK(\"\"http://example.invalid\"\")\",\"'+Synthetic Plus\",")
                .contains("\",\"'-Synthetic Minus\",", "\",\"'@Synthetic At\",");
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", 984_013L, "Empty Reporting Team");
        assertThat(exports.compliance(team(ReportFixture.MANAGER, 984_013L, TeamRole.MANAGER), null, null)
                        .content())
                .endsWith("\r\n" + csv("Total", "", "0", "0", "0", "0", ""));
    }

    @Test
    void refusedAndInvalidExportsAreNotAudited() {
        assertKind(() -> exports.aging(global(GlobalRole.SYSTEM_ADMIN)), Kind.FORBIDDEN);
        Actor manager = team(ReportFixture.MANAGER, ALPHA, TeamRole.MANAGER);
        assertKind(() -> exports.compliance(manager, "2026-02-01", "2026-01-31"), Kind.INVALID);
        entityManager.flush();

        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'REPORT_EXPORTED' AND ACTOR_ID IN (?, ?)",
                        Long.class,
                        ReportFixture.MANAGER,
                        984_009L))
                .isZero();
    }

    private ComplianceReport compliance(Actor actor) {
        return reports.compliance(actor, fixture.from().toString(), fixture.to().toString());
    }

    /** The fixture's overdue case at this insertion index, as the list shows it. */
    private OverdueLine overdue(int index, String owner, int days) {
        return new OverdueLine(caseOf(index), titleOf(index), owner, today.minusDays(days), days);
    }

    /** The fixture's open request on the case at this insertion index, asked by its Manager. */
    private WaitingLine waiting(int index, String askedOf, int days) {
        return new WaitingLine(
                caseOf(index), titleOf(index), askedOf, "Synthetic Report Manager", today.minusDays(days), days);
    }

    private static String caseOf(int index) {
        return "RPT-2026-" + (984_100 + index);
    }

    private static String titleOf(int index) {
        return "Synthetic report case " + (984_100 + index);
    }

    private String day(int daysAgo) {
        return today.minusDays(daysAgo).toString();
    }

    private static String csv(String... cells) {
        return CsvFormat.line(List.of(cells));
    }

    private static void assertKind(Runnable call, Kind kind) {
        assertThatThrownBy(call::run)
                .isInstanceOf(ReportException.class)
                .extracting(thrown -> ((DomainException) thrown).kind())
                .isEqualTo(kind);
    }

    private static Actor team(long user, long team, TeamRole role) {
        return new Actor(new UserPermissions(user, false, Set.of(), Map.of(team, role)), "Synthetic Report User");
    }

    private static Actor global(GlobalRole role) {
        return new Actor(new UserPermissions(984_009L, false, Set.of(role), Map.of()), "Synthetic Global User");
    }
}
