// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.validation.ConstraintViolationException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
 * Case creation and the case page's read on PostgreSQL: numbering, SLA and search row initialisation, the audit
 * event, who may create, which references are refused, and that unreadable, absent and malformed cases are all
 * "not found". {@code app.timezone} is UTC in the test profile.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseServiceIT {

    private static final String DESCRIPTION = "Synthetic description of a synthetic matter";
    private static final String SUBJECT = "Synthetic subject";

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
    private long analyst;
    private long reader;
    private LocalDate identifiedOn;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        analyst = seed.user();
        reader = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(reader, team, TeamRole.READER);
        identifiedOn = LocalDate.now(ZoneOffset.UTC).minusDays(3);
    }

    @Test
    void creatingACaseNumbersItAndInitialisesItsSlaSearchAndAuditRows() {
        final Instant before = Instant.now().truncatedTo(ChronoUnit.SECONDS);
        CaseNumber number = cases.create(actor(analyst, TeamRole.ANALYST), fields(analyst, "  Synthetic title  "));
        entityManager.flush();

        assertThat(number.prefix()).isEqualTo("CASE");
        assertThat(number.year()).isEqualTo(LocalDate.now(ZoneOffset.UTC).getYear());
        long id = seed.caseId(number);
        assertThat(jdbc.queryForMap("SELECT TITLE, STATUS, OWNER_ID, CREATED_BY FROM CASE_RECORD WHERE ID = ?", id))
                .containsEntry("TITLE", "Synthetic title")
                .containsEntry("STATUS", "OPEN")
                .containsEntry("OWNER_ID", analyst)
                .containsEntry("CREATED_BY", analyst);

        // The seed's policy: 30 days from the end of the identified-on day, at risk after 80 % (24 days).
        Instant anchor = identifiedOn.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        Map<String, Object> sla = jdbc.queryForMap("SELECT * FROM CASE_SLA WHERE CASE_ID = ?", id);
        assertThat(sla).containsEntry("SLA_STATE", "ON_TRACK").containsEntry("SLA_CYCLE", 1);
        assertThat(instant(sla, "SLA_DUE_AT")).isEqualTo(anchor.plus(30, ChronoUnit.DAYS));
        assertThat(instant(sla, "AT_RISK_AT")).isEqualTo(anchor.plus(24, ChronoUnit.DAYS));

        String search = jdbc.queryForObject("SELECT SEARCH_TEXT FROM CASE_SEARCH WHERE CASE_ID = ?", String.class, id);
        assertThat(search).contains("Synthetic title", DESCRIPTION).doesNotContain(SUBJECT);

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ENTITY_TYPE, ENTITY_ID, ACTION, NEW_VALUES_JSON, AT_TS FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ?",
                id);
        assertThat(event)
                .containsEntry("ACTOR_ID", analyst)
                .containsEntry("ENTITY_TYPE", "CASE")
                .containsEntry("ENTITY_ID", id)
                .containsEntry("ACTION", "CASE_CREATED");
        assertThat((String) event.get("NEW_VALUES_JSON"))
                .contains("\"title\":\"Synthetic title\"", "\"hmac\"")
                .doesNotContain(DESCRIPTION, SUBJECT);
        assertThat(instant(event, "AT_TS")).isAfterOrEqualTo(before);
    }

    @Test
    void numbersComeFromOneSequenceAndNeverRepeat() {
        CaseNumber first = cases.create(actor(analyst, TeamRole.ANALYST), fields(analyst, "First"));
        CaseNumber second = cases.create(actor(analyst, TeamRole.ANALYST), fields(analyst, "Second"));

        assertThat(second.sequence()).isGreaterThan(first.sequence());
    }

    @Test
    void readersSystemAdminsAndOtherTeamsMayNotCreate() {
        assertThatThrownBy(() -> cases.create(actor(reader, TeamRole.READER), fields(analyst, "Reader")))
                .satisfies(kind(Kind.FORBIDDEN));
        Actor systemAdmin = new Actor(
                new UserPermissions(seed.user(), false, Set.of(GlobalRole.SYSTEM_ADMIN), Map.of()), "Synthetic SA");
        assertThatThrownBy(() -> cases.create(systemAdmin, fields(analyst, "SA")))
                .satisfies(kind(Kind.FORBIDDEN));
        Actor otherTeam = new Actor(
                new UserPermissions(seed.user(), false, Set.of(), Map.of(seed.team(), TeamRole.MANAGER)), "Other");
        assertThatThrownBy(() -> cases.create(otherTeam, fields(analyst, "Other")))
                .satisfies(kind(Kind.FORBIDDEN));
        entityManager.flush();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CASE_RECORD WHERE TEAM_ID = ?", Long.class, team))
                .isZero();
    }

    @Test
    void referencesTheFormCouldNotOfferAreInvalid() {
        Actor actor = actor(analyst, TeamRole.ANALYST);
        CaseFields readerOwns = fields(reader, "Reader owns");
        assertThatThrownBy(() -> cases.create(actor, readerOwns)).satisfies(kind(Kind.INVALID));

        CaseFields future =
                with(fields(analyst, "Future"), LocalDate.now(ZoneOffset.UTC).plusDays(1));
        assertThatThrownBy(() -> cases.create(actor, future)).satisfies(kind(Kind.INVALID));

        jdbc.update("UPDATE CASE_TYPE SET ACTIVE = FALSE WHERE ID = ?", seed.caseType());
        entityManager.clear(); // the first attempt loaded the case type
        assertThatThrownBy(() -> cases.create(actor, fields(analyst, "Inactive type")))
                .satisfies(kind(Kind.INVALID));
        jdbc.update("UPDATE CASE_TYPE SET ACTIVE = TRUE WHERE ID = ?", seed.caseType());

        jdbc.update("UPDATE TEAM SET ACTIVE = FALSE WHERE ID = ?", team);
        entityManager.clear();
        assertThatThrownBy(() -> cases.create(actor, fields(analyst, "Inactive team")))
                .satisfies(kind(Kind.INVALID));
    }

    @Test
    void fieldsAreValidatedBeforeTheServiceRuns() {
        assertThatThrownBy(() -> cases.create(actor(analyst, TeamRole.ANALYST), fields(analyst, " ")))
                .isInstanceOf(ConstraintViolationException.class);
        assertThatThrownBy(() -> cases.create(actor(analyst, TeamRole.ANALYST), fields(analyst, "x".repeat(201))))
                .isInstanceOf(ConstraintViolationException.class);
    }

    @Test
    void theCasePageShowsWhatWasEnteredInThreeStatements() {
        final CaseNumber number = cases.create(actor(analyst, TeamRole.ANALYST), fields(analyst, "Viewed"));
        entityManager.flush();
        entityManager.clear();
        Statistics statistics =
                entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        statistics.clear();
        CaseView view;
        try {
            view = cases.view(actor(reader, TeamRole.READER), number.toString());
            // The case with its type and SLA row, the team's name, the owner's name.
            assertThat(statistics.getPrepareStatementCount()).isEqualTo(3);
        } finally {
            statistics.setStatisticsEnabled(false);
        }

        assertThat(view.number()).isEqualTo(number);
        assertThat(view.title()).isEqualTo("Viewed");
        assertThat(view.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(view.sensitivity()).isEqualTo(Sensitivity.STANDARD);
        assertThat(view.priority()).isEqualTo(CasePriority.HIGH);
        assertThat(view.caseTypeName()).startsWith("Seed ");
        assertThat(view.teamName()).isEqualTo("Seed Team " + team);
        assertThat(view.ownerName()).isEqualTo("Seed User " + analyst);
        assertThat(view.identifiedOn()).isEqualTo(identifiedOn);
        assertThat(view.openedOn()).isEqualTo(LocalDate.now(ZoneOffset.UTC));
        assertThat(view.subjectText()).isEqualTo(SUBJECT);
        assertThat(view.description()).isEqualTo(DESCRIPTION);
        assertThat(view.sla().state()).isEqualTo(SlaState.ON_TRACK);
        assertThat(view.sla().dueOn()).isEqualTo(identifiedOn.plusDays(31));
        assertThat(view.sla().daysLeft()).isEqualTo(28);
    }

    @Test
    void unreadableAbsentAndMalformedCasesAreAllNotFound() {
        CaseNumber restricted = cases.create(
                actor(analyst, TeamRole.ANALYST),
                new CaseFields(
                        seed.caseType(),
                        "Restricted",
                        CasePriority.NORMAL,
                        identifiedOn,
                        team,
                        analyst,
                        null,
                        DESCRIPTION,
                        Sensitivity.RESTRICTED));
        Actor readerActor = actor(reader, TeamRole.READER);

        assertThat(cases.view(actor(analyst, TeamRole.ANALYST), restricted.toString())
                        .title())
                .isEqualTo("Restricted");
        assertThatThrownBy(() -> cases.view(readerActor, restricted.toString())).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> cases.view(readerActor, "CASE-2026-999999")).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> cases.view(readerActor, "not-a-number")).satisfies(kind(Kind.NOT_FOUND));
    }

    @Test
    void casesWithoutAnSlaRowShowNoSla() {
        CaseNumber seeded = seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);

        CaseView view = cases.view(actor(analyst, TeamRole.ANALYST), seeded.toString());

        assertThat(view.sla()).isEqualTo(new CaseView.Sla(SlaState.NOT_APPLICABLE, null, 0));
    }

    private Actor actor(long userId, TeamRole role) {
        return new Actor(new UserPermissions(userId, false, Set.of(), Map.of(team, role)), "Synthetic Actor");
    }

    private CaseFields fields(long owner, String title) {
        return new CaseFields(
                seed.caseType(),
                title,
                CasePriority.HIGH,
                identifiedOn,
                team,
                owner,
                SUBJECT,
                DESCRIPTION,
                Sensitivity.STANDARD);
    }

    private static CaseFields with(CaseFields fields, LocalDate identifiedOn) {
        return new CaseFields(
                fields.caseTypeId(),
                fields.title(),
                fields.priority(),
                identifiedOn,
                fields.teamId(),
                fields.ownerId(),
                fields.subjectText(),
                fields.description(),
                fields.sensitivity());
    }

    private static Instant instant(Map<String, Object> row, String column) {
        return ((Timestamp) Objects.requireNonNull(row.get(column))).toInstant();
    }

    private static Consumer<Throwable> kind(Kind kind) {
        return thrown -> assertThat(thrown)
                .isInstanceOf(CaseException.class)
                .extracting(e -> ((DomainException) e).kind())
                .isEqualTo(kind);
    }
}
