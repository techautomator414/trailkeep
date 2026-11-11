// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import static io.trailkeep.user.ImportPeopleService.IMPORTER_ID;
import static io.trailkeep.user.ImportPeopleService.LEGACY_APPROVAL_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.closure.ClosureImportService.ImportedClosure;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/** {@link ClosureImportService} (WP-26 part h, design §7.1, §8.1 R6 8) on both vendors. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractClosureImportIT {

    private static final Instant CLOSED = Instant.parse("2019-06-01T09:30:00Z");

    @Autowired
    private ClosureImportService imports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private long caseId;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        long owner = seed.user();
        CaseNumber number = seed.caseRow(seed.team(), owner, Sensitivity.STANDARD, CaseStatus.CLOSED);
        caseId = seed.caseId(number);
    }

    @Test
    void importedClosureSkipsApproverEligibilityOnly() {
        long id = imports.importClosure(
                new ImportedClosure(caseId, "SUBSTANTIATED", "Synthetic summary", CLOSED, 77L, 88L));
        entityManager.flush();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT REQUESTED_BY, APPROVER_ID, DECIDED_BY, DECISION, OUTCOME FROM CLOSURE_REQUEST WHERE ID = ?",
                id);
        assertThat(number(row.get("REQUESTED_BY")))
                .as("requested by the import principal")
                .isEqualTo(IMPORTER_ID);
        assertThat(number(row.get("APPROVER_ID")))
                .as("approved by the legacy principal")
                .isEqualTo(LEGACY_APPROVAL_ID);
        assertThat(number(row.get("DECIDED_BY"))).isEqualTo(LEGACY_APPROVAL_ID);
        assertThat(row).containsEntry("DECISION", "APPROVED").containsEntry("OUTCOME", "SUBSTANTIATED");
        assertThat(jdbc.queryForObject("SELECT REQUESTED_AT FROM CLOSURE_REQUEST WHERE ID = ?", Timestamp.class, id))
                .isEqualTo(Timestamp.from(CLOSED));
        assertThat(jdbc.queryForObject("SELECT DECIDED_AT FROM CLOSURE_REQUEST WHERE ID = ?", Timestamp.class, id))
                .isEqualTo(Timestamp.from(CLOSED));
        assertThat(jdbc.queryForObject("SELECT IMPORTED FROM CLOSURE_REQUEST WHERE ID = ?", Boolean.class, id))
                .isTrue();
        assertThat(String.valueOf(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ENTITY_ID = ? AND ACTION = 'CLOSURE_IMPORTED'"
                                + " AND ORIGIN = 'IMPORT' AND ACTOR_ID = ?",
                        String.class,
                        id,
                        IMPORTER_ID)))
                .contains("\"runId\":77", "\"approverId\":88")
                .doesNotContain("Synthetic summary");
    }

    @Test
    void missingSummariesSaySoAndTheRulesStay() {
        long id = imports.importClosure(new ImportedClosure(caseId, "UNSUBSTANTIATED", null, CLOSED, 77L, 88L));

        assertThat(jdbc.queryForObject("SELECT RESOLUTION_SUMMARY FROM CLOSURE_REQUEST WHERE ID = ?", String.class, id))
                .isEqualTo(ClosureImportService.NO_SUMMARY);
        for (ImportedClosure refused : new ImportedClosure[] {
            new ImportedClosure(caseId, " ", null, CLOSED, 77L, 88L),
            new ImportedClosure(caseId, "X".repeat(41), null, CLOSED, 77L, 88L),
            new ImportedClosure(caseId, "UNSUBSTANTIATED", "s".repeat(ClosureRequest.MAX_TEXT + 1), CLOSED, 77L, 88L),
            new ImportedClosure(caseId, "UNSUBSTANTIATED", null, Instant.now().plusSeconds(86_400), 77L, 88L)
        }) {
            assertThatThrownBy(() -> imports.importClosure(refused))
                    .satisfies(e -> assertThat(((ClosureException) e).kind()).isEqualTo(Kind.INVALID));
        }
    }

    private static long number(@Nullable Object value) {
        return ((Number) Objects.requireNonNull(value, "column")).longValue();
    }
}
