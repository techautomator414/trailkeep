// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.notification.UserAlertService.Alert;
import io.trailkeep.notification.UserAlertService.AlertPage;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
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
 * In-app alerts (WP-17 part c): {@code notifyUser} writes the person's alert with their mail in the caller's
 * transaction (both roll back together; a person without an address still gets the alert, a disabled one gets
 * neither); a person lists only their own alerts, newest first, 50 to a page; marking read and deleting act only on
 * their own, and another person's alert is {@link AlertNotFoundException}. Rows are committed and removed after each
 * test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class UserAlertServiceIT {

    private static final long PERSON = 983_001L;
    private static final long OTHER = 983_002L;
    private static final Instant AT = Instant.parse("2026-11-06T09:00:00Z");
    private static final Map<String, String> MODEL =
            Map.of("caseNumber", "CASE-2026-000042", "title", "Synthetic title", "commenter", "Synthetic Colleague");

    @Autowired
    private NotificationService notifications;

    @Autowired
    private UserAlertService alerts;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        removeRows();
        user(PERSON, "person@example.invalid");
        user(OTHER, "other@example.invalid");
    }

    @AfterEach
    void removeRows() {
        jdbc.update("DELETE FROM USER_ALERT WHERE USER_ID IN (?, ?)", PERSON, OTHER);
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX WHERE RECIPIENT IN ('person@example.invalid', '')");
        jdbc.update("DELETE FROM APP_USER WHERE ID IN (?, ?)", PERSON, OTHER);
    }

    @Test
    void theAlertIsWrittenWithTheMailInTheCallersTransactionAndRolledBackWithIt() {
        Boolean mailed = transactions.execute(status ->
                notifications.notifyUser(PERSON, "comment-added", MODEL, null, "/cases/CASE-2026-000042#comments"));

        assertThat(mailed).isTrue();
        assertThat(jdbc.queryForMap("SELECT * FROM USER_ALERT WHERE USER_ID = ?", PERSON))
                .containsEntry("MESSAGE", "Synthetic Colleague commented on CASE-2026-000042, \"Synthetic title\"")
                .containsEntry("LINK", "/cases/CASE-2026-000042#comments")
                .containsEntry("READ_AT", null)
                .containsEntry("CASE_ID", null);
        assertThat(mailCount()).isEqualTo(1);

        transactions.executeWithoutResult(status -> {
            notifications.notifyUser(PERSON, "comment-added", MODEL, null, "/cases/CASE-2026-000042#comments");
            entityManager.flush();
            assertThat(alerts.unread(CaseScope.system(), PERSON)).isEqualTo(2);
            status.setRollbackOnly();
        });
        assertThat(alerts.unread(CaseScope.system(), PERSON)).isEqualTo(1);
        assertThat(mailCount()).isEqualTo(1);
    }

    @Test
    void personWithoutAddressGetsOnlyTheAlertAndDisabledPersonGetsNothing() {
        jdbc.update("UPDATE APP_USER SET EMAIL = '' WHERE ID = ?", PERSON);
        Boolean mailed = transactions.execute(
                status -> notifications.notifyUser(PERSON, "comment-added", MODEL, null, "/cases/x"));
        assertThat(mailed).isFalse();
        assertThat(alerts.unread(CaseScope.system(), PERSON)).isEqualTo(1);
        assertThat(mailCount()).isZero();

        jdbc.update("UPDATE APP_USER SET DISABLED = ? WHERE ID = ?", true, OTHER);
        transactions.executeWithoutResult(
                status -> notifications.notifyUser(OTHER, "comment-added", MODEL, null, "/cases/x"));
        assertThat(alerts.unread(CaseScope.system(), OTHER)).isZero();
    }

    @Test
    void personListsOnlyOwnAlertsNewestFirstFiftyToPage() {
        IntStream.range(0, UserAlertService.PAGE_SIZE + 1)
                .forEach(i -> alert(983_100L + i, PERSON, "Synthetic alert " + i, AT.plusSeconds(i)));
        alert(983_200L, OTHER, "Their alert", AT.plusSeconds(3600));

        AlertPage first = alerts.alerts(CaseScope.system(), PERSON, 1);
        assertThat(first.page()).isEqualTo(1);
        assertThat(first.pages()).isEqualTo(2);
        assertThat(first.total()).isEqualTo(51);
        assertThat(first.alerts()).hasSize(UserAlertService.PAGE_SIZE);
        assertThat(first.alerts().get(0).message()).isEqualTo("Synthetic alert 50");
        assertThat(first.alerts()).extracting(Alert::message).doesNotContain("Their alert");
        assertThat(alerts.alerts(CaseScope.system(), PERSON, 2).alerts())
                .extracting(Alert::message)
                .containsExactly("Synthetic alert 0");
        assertThat(alerts.alerts(CaseScope.system(), PERSON, 9).page()).isEqualTo(2);
        assertThat(alerts.alerts(CaseScope.system(), PERSON, 0).page()).isEqualTo(1);
        assertThat(alerts.unread(CaseScope.system(), PERSON)).isEqualTo(51);
    }

    @Test
    void markingReadAndDeletingTouchOnlyOwnAlerts() {
        alert(983_301L, PERSON, "Mine", AT);
        alert(983_302L, OTHER, "Theirs", AT);

        alerts.markRead(PERSON, 983_301L);
        alerts.markRead(PERSON, 983_301L);
        assertThat(alerts.unread(CaseScope.system(), PERSON)).isZero();
        assertThat(alerts.alerts(CaseScope.system(), PERSON, 1).alerts())
                .singleElement()
                .satisfies(alert -> assertThat(alert.read()).isTrue());

        assertThatThrownBy(() -> alerts.markRead(PERSON, 983_302L)).isInstanceOf(AlertNotFoundException.class);
        assertThatThrownBy(() -> alerts.delete(PERSON, 983_302L)).isInstanceOf(AlertNotFoundException.class);
        assertThatThrownBy(() -> alerts.delete(PERSON, -1L)).isInstanceOf(AlertNotFoundException.class);
        assertThat(alerts.unread(CaseScope.system(), OTHER)).isEqualTo(1);

        alerts.delete(PERSON, 983_301L);
        assertThat(alerts.alerts(CaseScope.system(), PERSON, 1).total()).isZero();
        assertThat(alerts.alerts(CaseScope.system(), OTHER, 1).total()).isEqualTo(1);
    }

    private long mailCount() {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM NOTIFICATION_OUTBOX WHERE RECIPIENT = 'person@example.invalid'", Long.class));
    }

    private void user(long id, String email) {
        jdbc.update(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, FALSE, CURRENT_TIMESTAMP)",
                id,
                "alert-service-subject-" + id,
                "Synthetic Person " + id,
                email);
    }

    private void alert(long id, long userId, String message, Instant at) {
        jdbc.update(
                "INSERT INTO USER_ALERT (ID, USER_ID, MESSAGE, LINK, CREATED_AT) VALUES (?, ?, ?, ?, ?)",
                id,
                userId,
                message,
                "/cases/x",
                Timestamp.from(at));
    }
}
