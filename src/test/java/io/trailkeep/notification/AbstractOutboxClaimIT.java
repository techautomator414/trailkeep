// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.notification.OutboxJdbcDao.Claimed;
import io.trailkeep.notification.OutboxJdbcDao.Outcome;
import io.trailkeep.testsupport.RequiresDocker;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The outbox claim, once per vendor (WP-17 part a, IMPLEMENTATION_PLAN A.1 #14): two dispatchers claiming the same due
 * rows at the same moment, round after round, never hold the same row, and between them claim every row exactly once;
 * the normal path marks a row sent exactly once; a retry waits for its next attempt and the last failure is final; a
 * claim that lapsed (its dispatcher died) is reaped and claimed again; a claim never builds an {@code IN} list over
 * {@link OutboxJdbcDao#MAX_CLAIM}. Rows are committed, as dispatchers see them, and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractOutboxClaimIT {

    private static final long FIRST_ID = 970_000_001L;
    private static final int ROWS = 600;

    @Autowired
    private OutboxJdbcDao outbox;

    @Autowired
    private JdbcTemplate jdbc;

    private Instant now;

    @BeforeEach
    void clear() {
        now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        removeTestRows();
    }

    @AfterEach
    void removeTestRows() {
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX WHERE ID >= ?", FIRST_ID);
    }

    @Test
    void twoConcurrentDispatchersNeverHoldTheSameRowAndTogetherClaimEveryRowOnce() throws Exception {
        insert(ROWS, now.minusSeconds(60));
        Set<Long> first = new HashSet<>();
        Set<Long> second = new HashSet<>();
        List<Long> everyClaim = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier together = new CyclicBarrier(2);
            int rounds = 0;
            while (rounds++ < ROWS) {
                // Different sizes, so the candidate lists overlap only partly and both dispatchers get rows.
                Future<List<Claimed>> a = pool.submit(() -> claimTogether(together, 7));
                Future<List<Claimed>> b = pool.submit(() -> claimTogether(together, 11));
                List<Long> fromA = ids(a.get(30, TimeUnit.SECONDS));
                List<Long> fromB = ids(b.get(30, TimeUnit.SECONDS));
                assertThat(Collections.disjoint(fromA, fromB))
                        .as("round %d: %s and %s", rounds, fromA, fromB)
                        .isTrue();
                first.addAll(fromA);
                second.addAll(fromB);
                everyClaim.addAll(fromA);
                everyClaim.addAll(fromB);
                if (fromA.isEmpty() && fromB.isEmpty()) {
                    break;
                }
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(everyClaim).doesNotHaveDuplicates().hasSize(ROWS);
        assertThat(Collections.disjoint(first, second)).isTrue();
        assertThat(first).as("both dispatchers took part").isNotEmpty();
        assertThat(second).as("both dispatchers took part").isNotEmpty();
        assertThat(count("STATUS = 'SENDING' AND LOCKED_UNTIL IS NOT NULL")).isEqualTo(ROWS);
    }

    @Test
    void theNormalPathMarksARowSentExactlyOnce() {
        insert(1, now.minusSeconds(1));
        List<Claimed> claimed = outbox.claim(now, now.plusSeconds(300), OutboxJdbcDao.MAX_CLAIM);
        assertThat(claimed).singleElement().satisfies(row -> {
            assertThat(row.recipient()).isEqualTo("claim-test@example.invalid");
            assertThat(row.subject()).isEqualTo("Synthetic claim test");
            assertThat(row.text()).isEqualTo("Synthetic body");
            assertThat(row.attempts()).isZero();
        });

        assertThat(outbox.claim(now, now.plusSeconds(300), OutboxJdbcDao.MAX_CLAIM))
                .isEmpty();
        assertThat(outbox.release(FIRST_ID, Outcome.SENT, now, null, null)).isTrue();
        assertThat(outbox.release(FIRST_ID, Outcome.SENT, now, null, null)).isFalse();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STATUS, ATTEMPTS, LOCKED_UNTIL, SENT_AT FROM NOTIFICATION_OUTBOX WHERE ID = ?", FIRST_ID);
        assertThat(row).containsEntry("STATUS", "SENT").containsEntry("LOCKED_UNTIL", null);
        assertThat(((Number) Objects.requireNonNull(row.get("ATTEMPTS"))).intValue())
                .isEqualTo(1);
        assertThat(row.get("SENT_AT")).isNotNull();
        assertThat(outbox.claim(now.plusSeconds(3600), now.plusSeconds(4000), 1))
                .isEmpty();
        assertThat(outbox.reap(now.plusSeconds(3600))).isZero();
    }

    @Test
    void retriesWaitForTheirNextAttemptAndTheLastFailureIsFinal() {
        insert(1, now.minusSeconds(1));
        outbox.claim(now, now.plusSeconds(300), 1);
        assertThat(outbox.release(FIRST_ID, Outcome.RETRY, now, now.plusSeconds(120), "x".repeat(600)))
                .isTrue();

        assertThat(outbox.claim(now.plusSeconds(60), now.plusSeconds(360), 1)).isEmpty();
        assertThat(outbox.claim(now.plusSeconds(120), now.plusSeconds(420), 1))
                .extracting(Claimed::attempts)
                .containsExactly(1);
        assertThat(outbox.release(FIRST_ID, Outcome.FAILED, now.plusSeconds(121), null, "refused"))
                .isTrue();
        assertThat(outbox.claim(now.plusSeconds(86_400), now.plusSeconds(90_000), 1))
                .isEmpty();
        Map<String, Object> row =
                jdbc.queryForMap("SELECT STATUS, ATTEMPTS, LAST_ERROR FROM NOTIFICATION_OUTBOX WHERE ID = ?", FIRST_ID);
        assertThat(row).containsEntry("STATUS", "FAILED").containsEntry("LAST_ERROR", "refused");
        assertThat(((Number) Objects.requireNonNull(row.get("ATTEMPTS"))).intValue())
                .isEqualTo(2);
    }

    @Test
    void lapsedClaimsAreReapedAndClaimedAgain() {
        insert(2, now.minusSeconds(1));
        assertThat(outbox.claim(now, now.plusSeconds(300), 2)).hasSize(2);
        assertThat(outbox.reap(now.plusSeconds(299))).as("leases still running").isZero();

        assertThat(outbox.reap(now.plusSeconds(301))).isEqualTo(2);
        assertThat(count("STATUS = 'PENDING' AND LOCKED_UNTIL IS NULL")).isEqualTo(2);
        assertThat(outbox.claim(now.plusSeconds(301), now.plusSeconds(600), 2))
                .extracting(Claimed::id)
                .containsExactlyInAnyOrder(FIRST_ID, FIRST_ID + 1);
    }

    @Test
    void claimsNeverBuildAnInListOverTheirLimit() {
        assertThat(OutboxJdbcDao.MAX_CLAIM).isLessThan(1000);
        // @Repository translates the IllegalArgumentException into Spring's API-usage exception.
        assertThatThrownBy(() -> outbox.claim(now, now.plusSeconds(300), OutboxJdbcDao.MAX_CLAIM + 1))
                .isInstanceOf(InvalidDataAccessApiUsageException.class)
                .hasMessageContaining("1 to 50");
        assertThatThrownBy(() -> outbox.claim(now, now.plusSeconds(300), 0))
                .isInstanceOf(InvalidDataAccessApiUsageException.class);
        insert(OutboxJdbcDao.MAX_CLAIM + 10, now.minusSeconds(1));
        assertThat(outbox.claim(now, now.plusSeconds(300), OutboxJdbcDao.MAX_CLAIM))
                .hasSize(OutboxJdbcDao.MAX_CLAIM)
                .extracting(Claimed::id)
                // The oldest 50, in no particular order: the locking statement has no ORDER BY.
                .containsExactlyInAnyOrderElementsOf(IntStream.range(0, OutboxJdbcDao.MAX_CLAIM)
                        .mapToObj(i -> FIRST_ID + i)
                        .toList());
    }

    private List<Claimed> claimTogether(CyclicBarrier together, int limit) throws Exception {
        together.await(30, TimeUnit.SECONDS);
        return outbox.claim(now, now.plusSeconds(300), limit);
    }

    private static List<Long> ids(List<Claimed> claimed) {
        return claimed.stream().map(Claimed::id).toList();
    }

    private void insert(int rows, Instant due) {
        OffsetDateTime at = OffsetDateTime.ofInstant(due, ZoneOffset.UTC);
        jdbc.batchUpdate(
                "INSERT INTO NOTIFICATION_OUTBOX (ID, RECIPIENT, TEMPLATE_ID, SUBJECT, BODY_TEXT, BODY_HTML,"
                        + " NEXT_ATTEMPT_AT, CREATED_AT) VALUES (?, 'claim-test@example.invalid', 'claim-test',"
                        + " 'Synthetic claim test', 'Synthetic body', '<p>Synthetic body</p>', ?, ?)",
                IntStream.range(0, rows)
                        .mapToObj(i -> new Object[] {FIRST_ID + i, at, at})
                        .toList());
    }

    private long count(String where) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE ID >= " + FIRST_ID + " AND " + where, Long.class);
        return count == null ? 0 : count;
    }
}
