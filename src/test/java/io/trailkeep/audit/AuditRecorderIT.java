// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.shared.observability.MdcKeys;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/** {@link AuditRecorder} against PostgreSQL: same-transaction semantics, request context, redaction at rest. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditRecorderIT {

    private static final String SECRET_SUBJECT = "Synthetic sensitive subject 4711";

    record Snapshot(String title, @Redacted String subject) {}

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTable() {
        // Test-only cleanup as the schema owner; the application user has no DELETE grant on AUDIT_EVENT.
        jdbc.update("DELETE FROM AUDIT_EVENT");
    }

    @AfterEach
    void clearRequestContext() {
        MDC.clear();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void anEventIsPersistedWhenTheEnclosingTransactionCommits() {
        transactions.executeWithoutResult(status -> recorder.record(entry(AuditChanges.none())));

        assertThat(count()).isOne();
    }

    @Test
    void anEventIsNotPersistedWhenTheEnclosingTransactionRollsBack() {
        transactions.executeWithoutResult(status -> {
            recorder.record(entry(AuditChanges.none()));
            status.setRollbackOnly();
        });

        assertThat(count()).isZero();
    }

    @Test
    void anExceptionAfterRecordingRollsTheEventBackWithTheChange() {
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    recorder.record(entry(AuditChanges.none()));
                    throw new IllegalStateException("business rule failed after the audit call");
                }))
                .isInstanceOf(IllegalStateException.class);

        assertThat(count()).isZero();
    }

    @Test
    void recordingOutsideTransactionIsRefused() {
        assertThatThrownBy(() -> recorder.record(entry(AuditChanges.none())))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThat(count()).isZero();
    }

    @Test
    void theRequestIdAndClientAddressOfTheCurrentRequestAreStored() {
        MDC.put(MdcKeys.REQUEST_ID, "req-123");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("192.0.2.10");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

        transactions.executeWithoutResult(status -> recorder.record(entry(AuditChanges.none())));

        Map<String, Object> row = jdbc.queryForMap("SELECT REQUEST_ID, CLIENT_IP, ORIGIN, AT_TS FROM AUDIT_EVENT");
        assertThat(row)
                .containsEntry("request_id", "req-123")
                .containsEntry("client_ip", "192.0.2.10")
                .containsEntry("origin", "UI");
        assertThat(row.get("at_ts")).isNotNull();
    }

    @Test
    void redactedValuesAreStoredOnlyAsHmacAndLength() {
        AuditChanges changes =
                recorder.diff(new Snapshot("Old", SECRET_SUBJECT), new Snapshot("New", SECRET_SUBJECT + " v2"));

        transactions.executeWithoutResult(status -> recorder.record(entry(changes)));

        Map<String, Object> row = jdbc.queryForMap("SELECT OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT");
        assertThat(row.values())
                .allSatisfy(json -> assertThat((String) json)
                        .doesNotContain(SECRET_SUBJECT, "sensitive", "4711")
                        .contains("\"hmac\":\"", "\"length\":"));
        assertThat((String) row.get("old_values_json")).contains("\"title\":\"Old\"");
    }

    private int count() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM AUDIT_EVENT", Integer.class);
        return count == null ? 0 : count;
    }

    private static AuditEntry entry(AuditChanges changes) {
        return new AuditEntry(41L, "Synthetic User", AuditOrigin.UI, 9L, "CASE", 9L, "UPDATE", changes);
    }
}
