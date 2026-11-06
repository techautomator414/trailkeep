// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The dispatcher against a mail server that is not there (the test profile's {@code 127.0.0.1:9}, where nothing
 * listens), WP-17's "SMTP down ⇒ rows stay {@code PENDING}": nothing is lost, each attempt is counted and the row waits
 * for its backoff; the tenth failure is final and keeps the error; and a pass gives up after one batch that sent
 * nothing, leaving the rest of the backlog untouched. Rows are committed, as the dispatcher sees them.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxDispatcherIT {

    private static final Map<String, String> MODEL =
            Map.of("name", "Synthetic Reader", "caseNumber", "CASE-2026-000042", "link", "https://example.invalid/x");

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    @AfterEach
    void emptyTheOutbox() {
        // Every row in this context's database, so rows other tests committed cannot take a place in a batch.
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX");
    }

    @Test
    void anUnreachableServerLeavesTheRowPendingWithOneAttemptAndALaterNextAttempt() {
        Instant before = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        long id = queue();

        assertThat(dispatcher.dispatch()).isZero();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM NOTIFICATION_OUTBOX WHERE ID = ?", id);
        assertThat(row)
                .containsEntry("STATUS", "PENDING")
                .containsEntry("ATTEMPTS", 1)
                .containsEntry("LOCKED_UNTIL", null)
                .containsEntry("SENT_AT", null);
        assertThat((String) row.get("LAST_ERROR")).startsWith("MailConnectException");
        assertThat(nextAttempt(id)).isAfterOrEqualTo(before.plus(Duration.ofMinutes(1)));
    }

    @Test
    void theTenthFailureIsFinalAndKeepsTheError() {
        long id = queue();
        for (int attempt = 1; attempt < OutboxDispatcher.MAX_ATTEMPTS; attempt++) {
            makeDue(id);
            dispatcher.dispatch();
        }
        assertThat(jdbc.queryForMap("SELECT STATUS, ATTEMPTS FROM NOTIFICATION_OUTBOX WHERE ID = ?", id))
                .containsEntry("STATUS", "PENDING")
                .containsEntry("ATTEMPTS", OutboxDispatcher.MAX_ATTEMPTS - 1);

        makeDue(id);
        dispatcher.dispatch();
        makeDue(id);
        dispatcher.dispatch();

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM NOTIFICATION_OUTBOX WHERE ID = ?", id);
        assertThat(row)
                .containsEntry("STATUS", "FAILED")
                .containsEntry("ATTEMPTS", OutboxDispatcher.MAX_ATTEMPTS)
                .containsEntry("LOCKED_UNTIL", null)
                .containsEntry("SENT_AT", null);
        assertThat((String) row.get("LAST_ERROR")).startsWith("MailConnectException");
    }

    @Test
    void passGivesUpAfterOneBatchThatSentNothing() {
        IntStream.range(0, OutboxJdbcDao.MAX_CLAIM + 10).forEach(i -> queue());

        assertThat(dispatcher.dispatch()).isZero();

        assertThat(pendingWith(1)).isEqualTo(OutboxJdbcDao.MAX_CLAIM);
        assertThat(pendingWith(0)).isEqualTo(10);
    }

    private long pendingWith(int attempts) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE STATUS = 'PENDING' AND ATTEMPTS = ?",
                Long.class,
                attempts));
    }

    private long queue() {
        return Objects.requireNonNull(transactions.execute(
                status -> notifications.enqueue("reader@example.invalid", "outbox-test", MODEL, null)));
    }

    private void makeDue(long id) {
        jdbc.update(
                "UPDATE NOTIFICATION_OUTBOX SET NEXT_ATTEMPT_AT = ? WHERE ID = ?",
                Timestamp.from(Instant.now().minusSeconds(1)),
                id);
    }

    private Instant nextAttempt(long id) {
        return Objects.requireNonNull(jdbc.queryForObject(
                        "SELECT NEXT_ATTEMPT_AT FROM NOTIFICATION_OUTBOX WHERE ID = ?", Timestamp.class, id))
                .toInstant();
    }
}
