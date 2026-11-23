// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.notification.OutboxResendService.Failed;
import io.trailkeep.notification.OutboxResendService.FailedMail;
import io.trailkeep.notification.OutboxResendService.Result;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
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
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;

/**
 * The audited re-send of failed mail (#318), once per vendor, on committed rows: a failed mail is listed with a masked
 * recipient, its case number only where the scope reads the case, and is put back to {@code PENDING} with attempts
 * reset and one audit event; a mail that is not failed, or about a case the scope cannot read, is skipped and audits
 * nothing; two admins re-queuing the same mail move it once and leave one event; the batch is bounded; only a
 * Compliance Admin may. Rows are removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractOutboxResendIT {

    private static final long FIRST_ID = 983_001L;
    private static final long ADMIN = 983_900L;
    private static final long OTHER_ADMIN = 983_901L;
    private static final UserPermissions COMPLIANCE_ADMIN =
            new UserPermissions(ADMIN, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of());
    private static final UserPermissions OTHER_COMPLIANCE_ADMIN =
            new UserPermissions(OTHER_ADMIN, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of());

    @Autowired
    private OutboxResendService resend;

    @Autowired
    private CaseScopeService scopes;

    @Autowired
    private JdbcTemplate jdbc;

    private CaseSeed seed;
    private long team;
    private long owner;
    private long analyst;
    private long caseId;
    private String caseNumber;

    @BeforeEach
    void seedCase() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = seed.user();
        analyst = seed.user();
        seed.member(owner, team, TeamRole.ANALYST);
        CaseNumber number = seed.caseRow(team, owner, Sensitivity.RESTRICTED, CaseStatus.OPEN);
        caseId = seed.caseId(number);
        caseNumber = number.toString();
    }

    @AfterEach
    void removeRows() {
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'NOTIFICATION_OUTBOX' AND ENTITY_ID >= ?", FIRST_ID);
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX WHERE ID >= ?", FIRST_ID);
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER WHERE ID IN (?, ?)", owner, analyst);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void failedMailIsListedWithAMaskedRecipientAndReasonAndTheCaseNumberOfAnAdminReadableCase() {
        failed(FIRST_ID, caseId, "SMTPAddressFailedException: 550 <alice@example.invalid> rejected");
        failed(FIRST_ID + 1, null, "MailConnectException: down");
        insert(FIRST_ID + 2, null, "SENT", 1, null);

        Failed listed = resend.failed(COMPLIANCE_ADMIN, CaseScope.system());

        assertThat(listed.total()).isEqualTo(2);
        assertThat(listed.mails())
                .extracting(FailedMail::id, FailedMail::recipient, FailedMail::caseNumber, FailedMail::attempts)
                .containsExactly(
                        tuple(FIRST_ID, "a***@example.invalid", caseNumber, 10),
                        tuple(FIRST_ID + 1, "a***@example.invalid", null, 10));
        assertThat(listed.mails().get(0).lastError())
                .isEqualTo("SMTPAddressFailedException: 550 <a***@example.invalid> rejected");
    }

    @Test
    void requeueingPutsTheMailBackToPendingWithAttemptsResetAndWritesOneAuditEventInTheSameChange() {
        failed(FIRST_ID, null, "MailConnectException: to alice@example.invalid failed");
        Instant before = Instant.now().minusSeconds(1);

        Result result = resend.requeue(COMPLIANCE_ADMIN, "Synthetic Admin", CaseScope.system(), List.of(FIRST_ID));

        assertThat(result).isEqualTo(new Result(1, 0));
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT STATUS, ATTEMPTS, LAST_ERROR, LOCKED_UNTIL, NEXT_ATTEMPT_AT FROM NOTIFICATION_OUTBOX"
                        + " WHERE ID = ?",
                FIRST_ID);
        assertThat(row)
                .containsEntry("STATUS", "PENDING")
                .containsEntry("LAST_ERROR", null)
                .containsEntry("LOCKED_UNTIL", null);
        assertThat(((Number) Objects.requireNonNull(row.get("ATTEMPTS"))).intValue())
                .isZero();
        assertThat(jdbc.queryForObject(
                        "SELECT NEXT_ATTEMPT_AT FROM NOTIFICATION_OUTBOX WHERE ID = ?", Timestamp.class, FIRST_ID))
                .isAfterOrEqualTo(Timestamp.from(before));
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ACTOR_NAME_SNAPSHOT, ORIGIN, CASE_ID, ACTION, OLD_VALUES_JSON, NEW_VALUES_JSON"
                        + " FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'NOTIFICATION_OUTBOX' AND ENTITY_ID = ?",
                FIRST_ID);
        assertThat(((Number) Objects.requireNonNull(event.get("ACTOR_ID"))).longValue())
                .isEqualTo(ADMIN);
        assertThat(event)
                .containsEntry("ACTOR_NAME_SNAPSHOT", "Synthetic Admin")
                .containsEntry("ORIGIN", "UI")
                .containsEntry("ACTION", "MAIL_REQUEUED");
        assertThat((String) event.get("OLD_VALUES_JSON"))
                .contains("\"status\":\"FAILED\"", "\"attempts\":10", "a***@example.invalid")
                .doesNotContain("alice@");
        assertThat((String) event.get("NEW_VALUES_JSON")).contains("\"status\":\"PENDING\"", "\"attempts\":0");
    }

    @Test
    void mailNoLongerFailedIsSkippedAndAuditsNothing() {
        failed(FIRST_ID, null, "x");
        insert(FIRST_ID + 1, null, "PENDING", 0, null);

        Result result = resend.requeue(
                COMPLIANCE_ADMIN, "Synthetic Admin", CaseScope.system(), List.of(FIRST_ID, FIRST_ID + 1, FIRST_ID + 9));
        Result again = resend.requeue(COMPLIANCE_ADMIN, "Synthetic Admin", CaseScope.system(), List.of(FIRST_ID));

        assertThat(result).isEqualTo(new Result(1, 2));
        assertThat(again).isEqualTo(new Result(0, 1));
        assertThat(audited(FIRST_ID)).isEqualTo(1);
        assertThat(audited(FIRST_ID + 1)).isZero();
    }

    @Test
    void mailAboutACaseTheScopeCannotReadIsNeitherListedNorRequeuedAndLeaksNothing() {
        failed(FIRST_ID, caseId, "restricted");
        failed(FIRST_ID + 1, null, "no case");
        // The case is restricted and the analyst is neither its owner nor a manager.
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseScope analystScope =
                scopes.forUser(new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)));

        Failed listed = resend.failed(COMPLIANCE_ADMIN, analystScope);
        Result result =
                resend.requeue(COMPLIANCE_ADMIN, "Synthetic Admin", analystScope, List.of(FIRST_ID, FIRST_ID + 1));

        assertThat(listed.mails()).extracting(FailedMail::id).containsExactly(FIRST_ID + 1);
        assertThat(listed.mails()).extracting(FailedMail::caseNumber).containsOnlyNulls();
        assertThat(result).isEqualTo(new Result(1, 1));
        assertThat(status(FIRST_ID)).isEqualTo("FAILED");
        assertThat(audited(FIRST_ID)).isZero();
    }

    @Test
    void twoAdminsRequeueingTheSameMailMoveItOnceAndLeaveOneAuditEvent() throws Exception {
        failed(FIRST_ID, null, "x");
        CyclicBarrier together = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Result> a = pool.submit(() -> {
                together.await(10, TimeUnit.SECONDS);
                return resend.requeue(COMPLIANCE_ADMIN, "Admin A", CaseScope.system(), List.of(FIRST_ID));
            });
            Future<Result> b = pool.submit(() -> {
                together.await(10, TimeUnit.SECONDS);
                return resend.requeue(OTHER_COMPLIANCE_ADMIN, "Admin B", CaseScope.system(), List.of(FIRST_ID));
            });
            Result first = a.get(30, TimeUnit.SECONDS);
            Result second = b.get(30, TimeUnit.SECONDS);

            assertThat(first.requeued() + second.requeued()).isEqualTo(1);
            assertThat(first.skipped() + second.skipped()).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(status(FIRST_ID)).isEqualTo("PENDING");
        assertThat(audited(FIRST_ID)).isEqualTo(1);
    }

    @Test
    void theBatchIsBoundedAndOnlyAComplianceAdminMayListOrRequeue() {
        List<Long> tooMany = IntStream.range(0, OutboxResendService.MAX_BATCH + 1)
                .mapToObj(i -> FIRST_ID + i)
                .toList();
        failed(FIRST_ID, null, "x");
        UserPermissions systemAdmin = new UserPermissions(ADMIN, false, Set.of(GlobalRole.SYSTEM_ADMIN), Map.of());
        UserPermissions analystOnly = new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST));

        assertThatThrownBy(() -> resend.requeue(COMPLIANCE_ADMIN, "A", CaseScope.system(), tooMany))
                .isInstanceOfSatisfying(
                        DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.INVALID));
        for (UserPermissions refused : List.of(systemAdmin, analystOnly)) {
            assertThatThrownBy(() -> resend.failed(refused, CaseScope.system()))
                    .isInstanceOfSatisfying(
                            DomainException.class, e -> assertThat(e.kind()).isEqualTo(DomainException.Kind.FORBIDDEN));
            assertThatThrownBy(() -> resend.requeue(refused, "A", CaseScope.system(), List.of(FIRST_ID)))
                    .isInstanceOf(DomainException.class);
        }
        assertThat(status(FIRST_ID)).isEqualTo("FAILED");
        assertThat(audited(FIRST_ID)).isZero();
    }

    private String status(long id) {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT STATUS FROM NOTIFICATION_OUTBOX WHERE ID = ?", String.class, id));
    }

    private long audited(long id) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'NOTIFICATION_OUTBOX' AND ENTITY_ID = ?"
                        + " AND ACTION = 'MAIL_REQUEUED'",
                Long.class,
                id);
        return count == null ? 0 : count;
    }

    private void failed(long id, @Nullable Long forCase, String error) {
        insert(id, forCase, "FAILED", 10, error);
    }

    private void insert(long id, @Nullable Long forCase, String status, int attempts, @Nullable String error) {
        Timestamp at = Timestamp.from(Instant.now().minusSeconds(60));
        jdbc.update(
                "INSERT INTO NOTIFICATION_OUTBOX (ID, RECIPIENT, TEMPLATE_ID, SUBJECT, BODY_TEXT, BODY_HTML, CASE_ID,"
                        + " STATUS, ATTEMPTS, NEXT_ATTEMPT_AT, SENT_AT, LAST_ERROR, CREATED_AT)"
                        + " VALUES (?, 'alice@example.invalid', 'comment-added', 'Synthetic', 'Synthetic body',"
                        + " '<p>Synthetic body</p>', ?, ?, ?, ?, ?, ?, ?)",
                id,
                new SqlParameterValue(Types.BIGINT, forCase),
                status,
                attempts,
                at,
                "SENT".equals(status) ? at : null,
                error,
                at);
    }
}
