// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Queuing mail (WP-17 part a): the row is written in the caller's transaction, so it commits or rolls back with the
 * change it reports, and without a transaction the call is refused; the subject and both bodies are rendered from
 * the template at once and the row is pending and due; bad recipients, template ids and subjects are refused.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class NotificationServiceIT {

    private static final Map<String, String> MODEL = Map.of(
            "name", "Synthetic <Reader>", "caseNumber", "CASE-2026-000042", "link", "https://example.invalid/cases/x");

    @Autowired
    private NotificationService notifications;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @AfterEach
    void removeQueuedMail() {
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX WHERE TEMPLATE_ID = 'outbox-test'");
    }

    @Test
    void mailIsRenderedAndQueuedInTheCallersTransactionAndRolledBackWithIt() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        Long id = transaction.execute(
                status -> notifications.enqueue(" reader@example.invalid ", "outbox-test", MODEL, null));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM NOTIFICATION_OUTBOX WHERE ID = ?", id);
        assertThat(row)
                .containsEntry("RECIPIENT", "reader@example.invalid")
                .containsEntry("TEMPLATE_ID", "outbox-test")
                .containsEntry("SUBJECT", "CASE-2026-000042: a synthetic test mail")
                .containsEntry("STATUS", "PENDING")
                .containsEntry("ATTEMPTS", 0)
                .containsEntry("CASE_ID", null)
                .containsEntry("LOCKED_UNTIL", null)
                .containsEntry("SENT_AT", null);
        assertThat((String) row.get("BODY_TEXT"))
                .startsWith("Hello Synthetic <Reader>,")
                .contains("https://example.invalid/cases/x")
                .doesNotContain("SPDX");
        assertThat((String) row.get("BODY_HTML"))
                .contains(
                        "Synthetic &lt;Reader&gt;", "<a href=\"https://example.invalid/cases/x\">CASE-2026-000042</a>")
                .doesNotContain("SPDX");
        assertThat(row.get("NEXT_ATTEMPT_AT")).isEqualTo(row.get("CREATED_AT"));

        transaction.executeWithoutResult(status -> {
            notifications.enqueue("rolled.back@example.invalid", "outbox-test", MODEL, null);
            status.setRollbackOnly();
        });
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE RECIPIENT = 'rolled.back@example.invalid'",
                        Long.class))
                .isZero();
    }

    @Test
    void mailIsNeverQueuedOutsideATransaction() {
        assertThatThrownBy(() -> notifications.enqueue("reader@example.invalid", "outbox-test", MODEL, null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void badRecipientsTemplateIdsAndSubjectsAreRefused() {
        TransactionTemplate transaction = new TransactionTemplate(transactions);
        for (String recipient :
                new String[] {"", "   ", "no-at-sign", "@example.invalid", "x".repeat(311) + "@e.invalid"}) {
            assertThatThrownBy(() ->
                            transaction.execute(status -> notifications.enqueue(recipient, "outbox-test", MODEL, null)))
                    .as(recipient)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        for (String template : new String[] {"../layout", "Outbox-Test", "outbox_test", "", "x".repeat(61)}) {
            assertThatThrownBy(() -> transaction.execute(
                            status -> notifications.enqueue("reader@example.invalid", template, MODEL, null)))
                    .as(template)
                    .isInstanceOf(IllegalArgumentException.class);
        }
        Map<String, String> injected =
                Map.of("name", "x", "caseNumber", "CASE\r\nBcc: someone@example.invalid", "link", "x");
        assertThatThrownBy(() -> transaction.execute(
                        status -> notifications.enqueue("reader@example.invalid", "outbox-test", injected, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE TEMPLATE_ID = 'outbox-test'", Long.class))
                .isZero();
    }
}
