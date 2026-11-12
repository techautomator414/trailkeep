// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.audit.ImportedHistoryService.ImportedEvent;
import io.trailkeep.audit.ImportedHistoryService.Kind;
import io.trailkeep.audit.SnapshotDiff.Redaction;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ImportedHistoryService} and {@code CASE_IMPORTED_EVENT} (WP-26 part v, design §7.5, §8.1 R6 11) on both
 * vendors: legacy time and actor name kept, plain values only for the plain fields of a non-note event, every other
 * value redacted exactly as audit redacts one, and the table's own checks.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractImportedHistoryIT {

    private static final Instant AT = Instant.parse("2018-03-04T05:06:07Z");
    private static final AtomicLong NEXT_RUN = new AtomicLong(987_000);
    private static final String HASH = "a".repeat(64);

    @Autowired
    private ImportedHistoryService history;

    @Autowired
    private SnapshotDiff snapshotDiff;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private long caseId;
    private long runId;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        long owner = seed.user();
        caseId = seed.caseId(seed.caseRow(seed.team(), owner, Sensitivity.STANDARD, CaseStatus.CLOSED));
        runId = NEXT_RUN.incrementAndGet();
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, UPDATED_AT)"
                        + " VALUES (?, 'IMPORT', 'synthetic', 'LOADING', ?, ?, ?)",
                runId,
                owner,
                Timestamp.from(AT),
                Timestamp.from(AT));
    }

    @Test
    void importedHistoryKeepsLegacyTimeAndRedacts() {
        long status = history.record(event("Legacy Clerk", Kind.STATUS_CHANGE, "status", "New", "In review"));
        String description = "Synthetic description, résumé 😀";
        final long described = history.record(event(null, Kind.FIELD_CHANGE, "description", null, description));
        final long note = history.record(event("Legacy Clerk", Kind.NOTE, "title", "Old title", "Note text"));
        final long other = history.record(event("Legacy Clerk", Kind.OTHER, "other", "a", "b"));
        entityManager.flush();

        Map<String, Object> plain = row(status);
        assertThat(plain)
                .containsEntry("WHO_NAME_SNAPSHOT", "Legacy Clerk")
                .containsEntry("KIND", "STATUS_CHANGE")
                .containsEntry("FIELD_NAME", "status");
        assertThat(plain).containsEntry("OLD_VALUE", "New").containsEntry("NEW_VALUE", "In review");
        assertThat(plain).containsEntry("OLD_HMAC", null).containsEntry("NEW_HMAC", null);
        assertThat(number(plain.get("RUN_ID"))).isEqualTo(runId);
        assertThat(number(plain.get("CASE_ID"))).isEqualTo(caseId);
        assertThat(jdbc.queryForObject("SELECT AT_TS FROM CASE_IMPORTED_EVENT WHERE ID = ?", Timestamp.class, status))
                .as("the legacy time, not the clock")
                .isEqualTo(Timestamp.from(AT));

        Redaction expected = snapshotDiff.redact(description);
        Map<String, Object> redacted = row(described);
        assertThat(redacted).containsEntry("WHO_NAME_SNAPSHOT", null).containsEntry("NEW_VALUE", null);
        assertThat(redacted).containsEntry("NEW_HMAC", expected.hmac()).containsEntry("OLD_HMAC", null);
        assertThat(number(redacted.get("NEW_LENGTH"))).as("code points").isEqualTo(31);
        assertThat(redacted.get("OLD_LENGTH")).isNull();

        for (long id : new long[] {note, other}) {
            Map<String, Object> hidden = row(id);
            assertThat(hidden).as("a note, and an unplaced field").containsEntry("OLD_VALUE", null);
            assertThat(hidden).containsEntry("NEW_VALUE", null);
            assertThat(hidden.get("OLD_HMAC")).isNotNull();
            assertThat(hidden.get("NEW_HMAC")).isNotNull();
        }
        assertThat(row(note))
                .containsEntry("NEW_HMAC", snapshotDiff.redact("Note text").hmac());
    }

    @Test
    void theRulesStayForEveryEvent() {
        String longText = "x".repeat(ImportedHistoryService.MAX_VALUE + 1);
        long redacted = history.record(event("Legacy Clerk", Kind.FIELD_CHANGE, "subject", null, longText));
        assertThat(redacted).as("a redacted value has no length limit").isPositive();

        for (ImportedEvent refused : new ImportedEvent[] {
            new ImportedEvent(caseId, Instant.now().plusSeconds(86_400), null, Kind.OTHER, null, null, null, runId),
            event("w".repeat(ImportedHistoryService.MAX_WHO + 1), Kind.OTHER, null, null, null),
            event("Legacy Clerk", Kind.FIELD_CHANGE, "legacyColour", "red", "blue"),
            event("Legacy Clerk", Kind.FIELD_CHANGE, "title", null, longText)
        }) {
            assertThatThrownBy(() -> history.record(refused)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void valuesAreNeverBothPlainAndRedacted() {
        assertThatThrownBy(() -> insert("FIELD_CHANGE", "New", HASH, 3))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void hashesAreNeverStoredWithoutTheirLength() {
        assertThatThrownBy(() -> insert("FIELD_CHANGE", null, HASH, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void kindsAreAClosedSet() {
        assertThatThrownBy(() -> insert("RENAMED", null, null, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /** Writes a row past the service, to reach the table's own checks. */
    private void insert(String kind, @Nullable String oldValue, @Nullable String oldHmac, @Nullable Integer oldLength) {
        jdbc.update(
                "INSERT INTO CASE_IMPORTED_EVENT (ID, CASE_ID, AT_TS, KIND, OLD_VALUE, OLD_HMAC, OLD_LENGTH, RUN_ID)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                NEXT_RUN.incrementAndGet(),
                caseId,
                Timestamp.from(AT),
                kind,
                new SqlParameterValue(Types.VARCHAR, oldValue),
                new SqlParameterValue(Types.VARCHAR, oldHmac),
                new SqlParameterValue(Types.INTEGER, oldLength),
                runId);
    }

    private ImportedEvent event(
            @Nullable String who, Kind kind, @Nullable String field, @Nullable String before, @Nullable String after) {
        return new ImportedEvent(caseId, AT, who, kind, field, before, after, runId);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap(
                "SELECT CASE_ID, WHO_NAME_SNAPSHOT, KIND, FIELD_NAME, OLD_VALUE, NEW_VALUE, OLD_HMAC, NEW_HMAC,"
                        + " OLD_LENGTH, NEW_LENGTH, RUN_ID FROM CASE_IMPORTED_EVENT WHERE ID = ?",
                id);
    }

    private static long number(@Nullable Object value) {
        return ((Number) Objects.requireNonNull(value, "column")).longValue();
    }
}
