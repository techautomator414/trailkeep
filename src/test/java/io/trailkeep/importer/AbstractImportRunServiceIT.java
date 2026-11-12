// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The importer's read API, once per vendor (WP-25 part d): no run reads as empty; the latest run is the one requested
 * last, and between two requested at the same instant the one with the larger id; its kind and status come back as
 * codes, and its loaded count only once loading has started. The table is emptied and filled inside the test's
 * transaction, which is rolled back.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractImportRunServiceIT {

    private static final Instant T = Instant.parse("2026-11-11T09:00:00Z");

    @Autowired
    private ImportRunService imports;

    @Autowired
    private JdbcTemplate jdbc;

    private long requester;

    @BeforeEach
    void emptyTheRuns() {
        // Every table that references IMPORT_RUN first, children before parents, so runs other importer ITs left in
        // the shared test database never block the delete.
        for (String table : List.of(
                "CASE_IMPORTED_EVENT",
                "IMPORT_STG_EVENT",
                "IMPORT_STG_ATTACHMENT",
                "IMPORT_STG_COMMENT",
                "IMPORT_STG_CASE",
                "IMPORT_STG_PERSON",
                "IMPORT_RUN_CHECKPOINT",
                "IMPORT_ISSUE",
                "IMPORT_RUN_SUMMARY",
                "IMPORT_RUN")) {
            // Test-only cleanup over the fixed table names of this list.
            jdbc.update("DELETE FROM " + table);
        }
        requester = new CaseSeed(jdbc).user();
    }

    @Test
    void noRunReadsAsEmpty() {
        assertThat(imports.latest()).isEmpty();
    }

    @Test
    void theLatestIsRequestedLastThenHasTheLargerId() {
        run(989_003L, "RELINK", "LOADED", T.minusSeconds(3_600), 40);
        run(989_001L, "IMPORT", "DRY_RUN", T, 0);
        run(989_002L, "IMPORT", "LOADING", T, 7);

        assertThat(imports.latest())
                .contains(new ImportRunService.RunSummary(989_002L, "IMPORT", "LOADING", T, T.plusSeconds(60), 7L));
    }

    @Test
    void theLoadedCountIsLeftOutBeforeLoadingStarts() {
        run(989_004L, "IMPORT", "APPROVED", T, 0);

        assertThat(imports.latest())
                .contains(new ImportRunService.RunSummary(989_004L, "IMPORT", "APPROVED", T, T.plusSeconds(60), null));
    }

    private void run(long id, String kind, String status, Instant requestedAt, long rowsLoaded) {
        jdbc.update(
                "INSERT INTO IMPORT_RUN (ID, KIND, SOURCE_ID, STATUS, REQUESTED_BY, REQUESTED_AT, ROWS_LOADED,"
                        + " UPDATED_AT) VALUES (?, ?, 'synthetic-a', ?, ?, ?, ?, ?)",
                id,
                kind,
                status,
                requester,
                Timestamp.from(requestedAt),
                rowsLoaded,
                Timestamp.from(requestedAt.plusSeconds(60)));
    }
}
