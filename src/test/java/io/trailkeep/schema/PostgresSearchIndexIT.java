// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * PostgreSQL's V21 search index, in every build (ADR-0017): {@code pg_trgm} is installed, {@code
 * CASE_SEARCH.SEARCH_TEXT} has a GIN trigram index, and the {@code LIKE} gateway's pattern, bound with its escape
 * character as the application binds it, can be answered from it.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class PostgresSearchIndexIT {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void searchTextHasATrigramIndex() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_trgm'", Long.class))
                .isOne();
        assertThat(jdbc.queryForObject(
                        "SELECT indexdef FROM pg_indexes WHERE tablename = 'case_search'"
                                + " AND indexname = 'ix_case_search_text'",
                        String.class))
                .contains("USING gin (search_text ")
                .endsWith("gin_trgm_ops)");
    }

    @Test
    @Transactional
    void theLikePatternIsAnsweredFromTheIndex() {
        // The test tables are nearly empty, where a scan is cheaper; forbid it, to see that the index can serve.
        jdbc.execute("SET LOCAL enable_seqscan = off");

        List<String> plan = jdbc.queryForList(
                "EXPLAIN SELECT CASE_ID FROM CASE_SEARCH WHERE SEARCH_TEXT LIKE ? ESCAPE '\\'",
                String.class,
                "%kestrel\\_ledger%");

        assertThat(String.join("\n", plan)).contains("Bitmap Index Scan on ix_case_search_text");
    }
}
