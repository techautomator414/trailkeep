// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.audit.AuditChanges;
import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.persistence.DatabaseProperties;
import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import jakarta.persistence.EntityManagerFactory;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The purge, once per vendor (WP-24 part c), run as the {@code TRAILKEEP_PURGE} user itself over its own connection,
 * so every statement is proven against the purge user's grants and nothing more. On committed rows ({@link
 * PurgeFixture}): an archived case past its {@code PURGE_AFTER} with a comment and its pin, a row of imported legacy
 * history, an update request answered
 * by that comment, an attachment and its chunk, search text, a closure request and the reopen that names it, and mail
 * about it, becomes a tombstone (description and subject cleared, {@code PURGED_AT} set, title and number kept, a new
 * version) with all of that deleted and one {@code CASE_PURGED} audit row of counts only, while its audit row, ledger
 * row, legal hold event and in-app alert stay; the run is recorded in {@code JOB_RUN}. A held case, a case not yet due
 * and a closed case are untouched; a hold placed between the scan and the purge wins; a second run purges nothing.
 * Audit ids the application's pooled optimizer allocates before and after a purge never collide with the purge's.
 * Report runs older than the retention go, and the mail naming them first; a recent run stays. Finally the {@code
 * purge} profile itself, started as the purge user with only {@code app.db.schema} naming the owner, finds every table
 * it touches and purges a due case.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractPurgeIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TestDatabaseUsers users;

    @Value("${app.db.purge-user}")
    private String purgeUser = "";

    @Autowired
    private DatabaseProperties database;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private AuditRecorder audit;

    @Autowired
    private TransactionTemplate appTransactions;

    private Connection connection;
    private PurgeJdbcDao purge;
    private PurgeFixture fixture;
    private Instant now = Instant.EPOCH;

    @BeforeEach
    void connectAsThePurgeUser() throws SQLException {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        connection = users.login(purgeUser);
        if (database.vendor() == DatabaseVendor.ORACLE) {
            try (Statement statement = connection.createStatement()) {
                // Test-only: this hand-made connection is not from the pool, so it sets the owner schema itself, as
                // the application's pool does from app.db.schema (SessionSchema).
                statement.execute("ALTER SESSION SET CURRENT_SCHEMA = " + users.ownerSchema());
            }
        }
        SingleConnectionDataSource source = new SingleConnectionDataSource(connection, true);
        purge = new PurgeJdbcDao(
                new NamedParameterJdbcTemplate(source),
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                entityManagerFactory);
        fixture = new PurgeFixture(jdbc, now);
    }

    @AfterEach
    void removeCommittedRows() throws SQLException {
        connection.close();
        // Test-only cleanup of the committed fixture, as the schema owner.
        fixture.remove();
    }

    @Test
    void anArchivedCasePastItsDateBecomesATombstoneAndKeepsItsHistory() {
        final long due = fixture.archived(now.minus(Duration.ofDays(1)), false);
        final long held = fixture.archived(now.minus(Duration.ofDays(1)), true);
        final long notYet = fixture.archived(now.plus(Duration.ofDays(1)), false);
        final long closed = fixture.kase(CaseStatus.CLOSED, null, false);
        for (long id : List.of(due, held, notYet, closed)) {
            fixture.content(id);
        }

        Map<String, Integer> counts = job().run(now);

        assertThat(counts)
                .containsEntry("cases", 1)
                .containsEntry("comments", 1)
                .containsEntry("pins", 1);
        assertThat(counts)
                .containsEntry("attachments", 1)
                .containsEntry("chunks", 1)
                .containsEntry("reopens", 1);
        assertThat(counts).containsEntry("closureRequests", 1).containsEntry("updateRequests", 1);
        assertThat(counts).containsEntry("importedEvents", 1);
        Map<String, Object> tombstone =
                jdbc.queryForMap("SELECT TITLE, DESCRIPTION, SUBJECT_TEXT, VERSION FROM CASE_RECORD WHERE ID = ?", due);
        assertThat(tombstone.get("TITLE")).isEqualTo("Seed case " + due);
        assertThat(tombstone.get("DESCRIPTION")).isNull();
        assertThat(tombstone.get("SUBJECT_TEXT")).isNull();
        assertThat(((Number) Objects.requireNonNull(tombstone.get("VERSION"))).intValue())
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT PURGED_AT FROM CASE_RECORD WHERE ID = ?", Timestamp.class, due))
                .isEqualTo(Timestamp.from(now));
        assertThat(fixture.contentRows(due)).isZero();
        assertThat(fixture.historyRows(due)).as("the four kept and CASE_PURGED").isEqualTo(5);
        Map<String, Object> purged = jdbc.queryForMap(
                "SELECT ORIGIN, ACTOR_ID, ENTITY_TYPE, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ? AND ACTION = 'CASE_PURGED'",
                due);
        assertThat(purged).containsEntry("ORIGIN", "SCHEDULER").containsEntry("ACTOR_ID", null);
        assertThat(purged).containsEntry("ENTITY_TYPE", "CASE").containsEntry("OLD_VALUES_JSON", null);
        assertThat(purged.get("NEW_VALUES_JSON"))
                .isEqualTo("{\"comments\":1,\"pins\":1,\"importedEvents\":1,\"attachments\":1,\"updateRequests\":1,"
                        + "\"closureRequests\":1,\"reopens\":1,\"mail\":1}");
        assertThat(fixture.count("SELECT LAST_ITEMS FROM JOB_RUN WHERE JOB_NAME = ?", PurgeJob.JOB))
                .isGreaterThanOrEqualTo(1);
        for (long id : List.of(held, notYet, closed)) {
            assertThat(fixture.contentRows(id)).as("case %s", id).isEqualTo(10);
            assertThat(fixture.historyRows(id)).as("case %s", id).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT PURGED_AT FROM CASE_RECORD WHERE ID = ?", Timestamp.class, id))
                    .isNull();
        }

        assertThat(job().run(now)).containsEntry("reportRuns", 0);
        assertThat(purge.due(now)).doesNotContain(due);
    }

    @Test
    void auditIdsTheApplicationAllocatesAroundAPurgeNeverCollideWithThePurges() {
        final long probe = fixture.anyCase();
        appAudits(probe, 1);
        long first = fixture.archived(now.minus(Duration.ofDays(1)), false);
        long second = fixture.archived(now.minus(Duration.ofDays(1)), false);
        job().run(now);

        appAudits(probe, 120);

        List<Long> purgeIds = jdbc.queryForList(
                "SELECT ID FROM AUDIT_EVENT WHERE ACTION = 'CASE_PURGED' AND CASE_ID IN (?, ?)",
                Long.class,
                first,
                second);
        List<Long> appIds = jdbc.queryForList(
                "SELECT ID FROM AUDIT_EVENT WHERE ACTION = 'PURGE_ID_PROBE' AND CASE_ID = ?", Long.class, probe);
        assertThat(purgeIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(appIds).hasSize(121).doesNotHaveDuplicates().doesNotContainAnyElementsOf(purgeIds);
    }

    @Test
    void holdsPlacedBetweenTheScanAndThePurgeWin() {
        long id = fixture.archived(now.minus(Duration.ofDays(1)), false);
        fixture.content(id);
        assertThat(purge.due(now)).contains(id);
        jdbc.update("UPDATE CASE_RECORD SET LEGAL_HOLD = ? WHERE ID = ?", true, id);

        assertThat(purge.purge(List.of(id), now)).isEmpty();
        assertThat(fixture.contentRows(id)).isEqualTo(10);
        assertThat(fixture.historyRows(id)).isEqualTo(4);
    }

    @Test
    void oldReportRunsGoWithTheMailNamingThemAndRecentOnesStay() {
        long old = fixture.run(now.minus(Duration.ofDays(401)));
        long recent = fixture.run(now.minus(Duration.ofDays(7)));

        assertThat(purge.trimReportRuns(now.minus(Duration.ofDays(400)))).isEqualTo(1);

        assertThat(jdbc.queryForList("SELECT ID FROM REPORT_RUN WHERE TEAM_ID = ?", Long.class, fixture.team()))
                .containsExactly(recent);
        assertThat(fixture.count("SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE REPORT_RUN_ID = ?", old))
                .isZero();
        assertThat(fixture.count("SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE REPORT_RUN_ID = ?", recent))
                .isEqualTo(1);
    }

    @Test
    void thePurgeProfileRunsAsThePurgeUserWithTheOwnerSchemaFromConfiguration() throws SQLException {
        long due = fixture.archived(now.minus(Duration.ofDays(1)), false);
        fixture.content(due);
        String vendorProfile = database.vendor() == DatabaseVendor.ORACLE ? "oracle-test" : "test";

        // As the retention CronJob starts it: the purge profile, the purge user's own pool, the owner named only by
        // app.db.schema. The runner purges once during start-up.
        new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(vendorProfile, "purge")
                .properties(
                        "spring.datasource.url=" + users.jdbcUrl(),
                        "spring.datasource.username=" + purgeUser,
                        "spring.datasource.password=" + users.newPassword(purgeUser),
                        "app.db.schema=" + users.ownerSchema())
                .run()
                .close();

        assertThat(jdbc.queryForObject("SELECT PURGED_AT FROM CASE_RECORD WHERE ID = ?", Timestamp.class, due))
                .isNotNull();
        assertThat(fixture.contentRows(due)).isZero();
    }

    /** Audit rows written by the application, their ids from Hibernate's pooled optimizer over the same sequence. */
    private void appAudits(long caseId, int rows) {
        appTransactions.executeWithoutResult(status -> {
            for (int i = 0; i < rows; i++) {
                audit.record(new AuditEntry(
                        null, null, AuditOrigin.SYSTEM, caseId, "CASE", caseId, "PURGE_ID_PROBE", AuditChanges.none()));
            }
        });
    }

    private PurgeJob job() {
        return new PurgeJob(purge, InstantSource.system(), Duration.ofDays(400));
    }
}
