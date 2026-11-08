// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseRetentionService;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The archive job, once per vendor (WP-24 part a): with case types kept 0, 1 and 7 years, one run archives exactly the
 * closed cases whose retention has passed (the boundary included) and that are not on legal hold, sets {@code
 * ARCHIVED_AT}, {@code PURGE_AFTER} (30 days later) and a new version on each, and audits {@code CASE_ARCHIVED} with
 * origin {@code SCHEDULER} for each; open, younger, held and already archived cases are left as they are, and a second
 * run archives nothing. A case reopened between the scan and the archive is skipped, unaudited. The job's health is
 * unknown before its first run, up after it and down when stale.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractArchiveJobIT {

    private static final AtomicLong TYPES = new AtomicLong(985_500);

    @Autowired
    private ArchiveJob job;

    @Autowired
    private CaseRetentionService retention;

    @Autowired
    private ArchiveHealthIndicator health;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private InstantSource clock;

    private CaseSeed seed;
    private Instant now;
    private long owner;
    private long team;

    @BeforeEach
    void seed() {
        // Both vendors keep timestamps to the microsecond at most; milliseconds compare exactly after the round trip.
        now = clock.instant().truncatedTo(ChronoUnit.MILLIS);
        seed = new CaseSeed(jdbc);
        owner = seed.user();
        team = seed.team();
    }

    @Test
    void oneRunArchivesTheClosedCasesPastTheirRetentionAndNothingElse() {
        long oneYear = caseType(1);
        long none = caseType(0);
        long sevenYears = caseType(7);
        final long pastYear = closed(oneYear, years(1).minus(Duration.ofDays(1)), false);
        final long onTheBoundary = closed(oneYear, years(1), false);
        final long immediately = closed(none, now.minus(Duration.ofMinutes(1)), false);
        final long notYet = closed(oneYear, years(1).plus(Duration.ofDays(1)), false);
        final long held = closed(oneYear, years(2), true);
        final long young = closed(sevenYears, years(3), false);
        final long open = kase(oneYear, CaseStatus.OPEN, null, false);
        final long archived = kase(oneYear, CaseStatus.ARCHIVED, years(5), false);
        jdbc.update("DELETE FROM JOB_RUN WHERE JOB_NAME = ?", ArchiveJob.JOB);
        assertThat(health.health().getStatus()).isEqualTo(Status.UNKNOWN);

        assertThat(job.run(now)).isGreaterThanOrEqualTo(3);

        for (long id : List.of(pastYear, onTheBoundary, immediately)) {
            Map<String, Object> row = row(id);
            assertThat(row.get("STATUS")).as("case %s", id).isEqualTo("ARCHIVED");
            assertThat(instant(id, "ARCHIVED_AT")).isEqualTo(now);
            assertThat(instant(id, "PURGE_AFTER")).isEqualTo(now.plus(Duration.ofDays(30)));
            assertThat(((Number) Objects.requireNonNull(row.get("VERSION"))).intValue())
                    .isEqualTo(1);
            assertThat(audits(id)).containsExactly("SCHEDULER");
        }
        for (long id : List.of(notYet, held, young)) {
            assertThat(row(id).get("STATUS")).as("case %s", id).isEqualTo("CLOSED");
            assertThat(row(id).get("ARCHIVED_AT")).isNull();
        }
        assertThat(row(open).get("STATUS")).isEqualTo("OPEN");
        assertThat(row(archived).get("ARCHIVED_AT")).isNull();
        for (long id : List.of(notYet, held, young, open, archived)) {
            assertThat(audits(id)).as("case %s", id).isEmpty();
        }
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        assertThat(retention.dueForArchive(now)).doesNotContain(pastYear, onTheBoundary, immediately);
        job.run(now);
        assertThat(audits(pastYear)).hasSize(1);

        jdbc.update(
                "UPDATE JOB_RUN SET LAST_SUCCESS_AT = ? WHERE JOB_NAME = ?",
                Timestamp.from(clock.instant().minus(Duration.ofHours(3))),
                ArchiveJob.JOB);
        entityManager.clear();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    void caseReopenedOrHeldBetweenTheScanAndTheArchiveIsLeftAlone() {
        long oneYear = caseType(1);
        long reopened = closed(oneYear, years(2), false);
        long putOnHold = closed(oneYear, years(2), false);
        long archives = closed(oneYear, years(2), false);
        List<Long> due = retention.dueForArchive(now);
        assertThat(due).contains(reopened, putOnHold, archives);

        jdbc.update("UPDATE CASE_RECORD SET STATUS = 'OPEN', CLOSED_AT = NULL WHERE ID = ?", reopened);
        jdbc.update("UPDATE CASE_RECORD SET LEGAL_HOLD = ? WHERE ID = ?", true, putOnHold);

        assertThat(retention.archive(List.of(reopened, putOnHold, archives), now, Duration.ofDays(30)))
                .isEqualTo(1);
        assertThat(row(reopened).get("STATUS")).isEqualTo("OPEN");
        assertThat(row(putOnHold).get("STATUS")).isEqualTo("CLOSED");
        assertThat(row(archives).get("STATUS")).isEqualTo("ARCHIVED");
        assertThat(audits(reopened)).isEmpty();
        assertThat(audits(putOnHold)).isEmpty();
        assertThat(audits(archives)).containsExactly("SCHEDULER");
    }

    private Instant years(int years) {
        return now.atZone(ZoneOffset.UTC).minusYears(years).toInstant();
    }

    private long caseType(int retentionYears) {
        long policy = Objects.requireNonNull(
                jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, seed.caseType()));
        long id = TYPES.incrementAndGet();
        jdbc.update(
                "INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID, RETENTION_YEARS) VALUES (?, ?, ?, ?)",
                id,
                "Retention " + id,
                policy,
                retentionYears);
        return id;
    }

    private long closed(long caseType, Instant closedAt, boolean held) {
        return kase(caseType, CaseStatus.CLOSED, closedAt, held);
    }

    private long kase(long caseType, CaseStatus status, @Nullable Instant closedAt, boolean held) {
        long id = seed.caseId(seed.caseRow(team, owner, Sensitivity.STANDARD, status));
        jdbc.update(
                "UPDATE CASE_RECORD SET CASE_TYPE_ID = ?, CLOSED_AT = ?, LEGAL_HOLD = ? WHERE ID = ?",
                caseType,
                closedAt == null ? null : Timestamp.from(closedAt),
                held,
                id);
        return id;
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("SELECT STATUS, ARCHIVED_AT, PURGE_AFTER, VERSION FROM CASE_RECORD WHERE ID = ?", id);
    }

    private List<String> audits(long caseId) {
        entityManager.flush();
        return jdbc.queryForList(
                "SELECT ORIGIN FROM AUDIT_EVENT WHERE ACTION = 'CASE_ARCHIVED' AND CASE_ID = ? AND ACTOR_ID IS NULL",
                String.class,
                caseId);
    }

    /** A timestamp column read as a {@code Timestamp}, which both drivers convert to. */
    private Instant instant(long id, String column) {
        return Objects.requireNonNull(
                        jdbc.queryForObject("SELECT " + column + " FROM CASE_RECORD WHERE ID = ?", Timestamp.class, id))
                .toInstant();
    }
}
