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
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Edit on PostgreSQL (WP-12): the saved fields, the {@code CASE_UPDATED} audit diff with subject and description
 * redacted, the rebuilt search text, optimistic locking both before and at flush, who may edit, and that an
 * unreadable case is "not found" before anything else is said.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseEditServiceIT {

    private static final String DESCRIPTION = "Synthetic description of a synthetic matter";
    private static final String SUBJECT = "Synthetic subject";

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private CaseSeed seed;
    private long team;
    private long analyst;
    private long reader;
    private CaseNumber number;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        analyst = seed.user();
        reader = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
        seed.member(reader, team, TeamRole.READER);
        number = cases.create(analystActor(), fields(Sensitivity.STANDARD));
        entityManager.flush();
    }

    @Test
    void anEditSavesTheFieldsAuditsARedactedDiffAndRebuildsTheSearchText() {
        EditableCase before = cases.editable(analystActor(), number.toString());
        assertThat(before.current())
                .isEqualTo(new CaseChanges("Synthetic title", CasePriority.NORMAL, SUBJECT, DESCRIPTION));
        assertThat(before.updatedBy()).isEqualTo("Seed User " + analyst);

        cases.edit(
                analystActor(),
                number.toString(),
                before.version(),
                new CaseChanges("  Edited title ", CasePriority.HIGH, " ", "Edited synthetic description"));
        entityManager.flush();

        long id = seed.caseId(number);
        assertThat(jdbc.queryForMap("SELECT TITLE, PRIORITY, SUBJECT_TEXT, VERSION FROM CASE_RECORD WHERE ID = ?", id))
                .containsEntry("TITLE", "Edited title")
                .containsEntry("PRIORITY", "HIGH")
                .containsEntry("SUBJECT_TEXT", null)
                .containsEntry("VERSION", before.version() + 1);
        assertThat(jdbc.queryForObject("SELECT SEARCH_TEXT FROM CASE_SEARCH WHERE CASE_ID = ?", String.class, id))
                .isEqualTo("edited title\nedited synthetic description");
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ENTITY_ID, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ? AND ACTION = 'CASE_UPDATED'",
                id);
        assertThat(event).containsEntry("ACTOR_ID", analyst).containsEntry("ENTITY_ID", id);
        assertThat((String) event.get("OLD_VALUES_JSON"))
                .contains("\"title\":\"Synthetic title\"", "\"priority\":\"NORMAL\"", "\"subjectText\"", "\"hmac\"")
                .doesNotContain(SUBJECT, DESCRIPTION);
        assertThat((String) event.get("NEW_VALUES_JSON"))
                .contains("\"title\":\"Edited title\"", "\"priority\":\"HIGH\"", "\"description\"", "\"hmac\"")
                .doesNotContain("Edited synthetic description");
    }

    @Test
    void savingUnchangedValuesWritesNothing() {
        EditableCase before = cases.editable(analystActor(), number.toString());

        cases.edit(analystActor(), number.toString(), before.version(), before.current());
        entityManager.flush();

        assertThat(cases.editable(analystActor(), number.toString()).version()).isEqualTo(before.version());
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'CASE_UPDATED'",
                        Long.class,
                        seed.caseId(number)))
                .isZero();
    }

    @Test
    void staleVersionsAreConflictsAndChangeNothing() {
        int version = cases.editable(analystActor(), number.toString()).version();
        cases.edit(analystActor(), number.toString(), version, changes("First save"));
        entityManager.flush();

        assertThatThrownBy(() -> cases.edit(analystActor(), number.toString(), version, changes("Second save")))
                .satisfies(kind(Kind.CONFLICT));
        assertThat(jdbc.queryForObject("SELECT TITLE FROM CASE_RECORD WHERE ID = ?", String.class, seed.caseId(number)))
                .isEqualTo("First save");
    }

    @Test
    void savesThatLoseTheRaceAtFlushAreConflicts() {
        long id = seed.caseId(number);
        CaseRecord loaded = entityManager.find(CaseRecord.class, id);
        int version = loaded.getVersion();
        // Another transaction committed between this one's read and its write: the row's version has moved on.
        jdbc.update("UPDATE CASE_RECORD SET VERSION = VERSION + 1 WHERE ID = ?", id);

        assertThatThrownBy(() -> cases.edit(analystActor(), number.toString(), version, changes("Lost the race")))
                .satisfies(kind(Kind.CONFLICT));
    }

    @Test
    void readersAndCasesNoLongerOpenMayNotBeEdited() {
        Actor readerActor = actor(reader, TeamRole.READER);
        assertThatThrownBy(() -> cases.editable(readerActor, number.toString())).satisfies(kind(Kind.FORBIDDEN));
        assertThatThrownBy(() -> cases.edit(readerActor, number.toString(), 0, changes("Reader")))
                .satisfies(kind(Kind.FORBIDDEN));

        CaseNumber pending = seed.caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.PENDING_CLOSURE);
        assertThatThrownBy(() -> cases.edit(analystActor(), pending.toString(), 0, changes("Pending")))
                .satisfies(kind(Kind.FORBIDDEN));
        assertThat(cases.view(analystActor(), pending.toString()).editable()).isFalse();
        assertThat(cases.view(analystActor(), number.toString()).editable()).isTrue();
        assertThat(cases.view(readerActor, number.toString()).editable()).isFalse();
    }

    @Test
    void unreadableCasesAreNotFoundBeforeAnythingElse() {
        long otherAnalyst = seed.user();
        seed.member(otherAnalyst, team, TeamRole.ANALYST);
        CaseNumber restricted = cases.create(analystActor(), fields(Sensitivity.RESTRICTED));
        Actor other = actor(otherAnalyst, TeamRole.ANALYST);

        assertThatThrownBy(() -> cases.editable(other, restricted.toString())).satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> cases.edit(other, restricted.toString(), 99, changes("Probe")))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> cases.edit(other, "CASE-2026-999999", 0, changes("Probe")))
                .satisfies(kind(Kind.NOT_FOUND));
        assertThatThrownBy(() -> cases.edit(other, "not-a-number", 0, changes("Probe")))
                .satisfies(kind(Kind.NOT_FOUND));
    }

    @Test
    void editsAreValidatedBeforeTheServiceRuns() {
        assertThatThrownBy(() -> cases.edit(
                        analystActor(), number.toString(), 0, new CaseChanges(" ", CasePriority.NORMAL, null, "x")))
                .isInstanceOf(ConstraintViolationException.class);
    }

    private Actor analystActor() {
        return actor(analyst, TeamRole.ANALYST);
    }

    private Actor actor(long userId, TeamRole role) {
        return new Actor(new UserPermissions(userId, false, Set.of(), Map.of(team, role)), "Synthetic Actor");
    }

    private CaseFields fields(Sensitivity sensitivity) {
        return new CaseFields(
                seed.caseType(),
                "Synthetic title",
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(3),
                team,
                analyst,
                SUBJECT,
                DESCRIPTION,
                sensitivity);
    }

    private static CaseChanges changes(String title) {
        return new CaseChanges(title, CasePriority.NORMAL, SUBJECT, DESCRIPTION);
    }

    private static Consumer<Throwable> kind(Kind kind) {
        return thrown -> assertThat(thrown)
                .isInstanceOf(CaseException.class)
                .extracting(e -> ((DomainException) e).kind())
                .isEqualTo(kind);
    }
}
