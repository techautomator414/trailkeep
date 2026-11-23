// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.perf;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.context.ActiveProfiles;

/**
 * The 500k seed on PostgreSQL (WP-30), run by the {@code perf-it} profile's {@code perf-500k} execution in a JVM and
 * container of its own, by hand and every night. The table goes to {@code target/perf/comments-500k-postgresql.md}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("perf-500k")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class PostgresComments500kPerfIT extends AbstractComments500kPerfIT {

    private static final PerfReport REPORT = new PerfReport(
            "50 000 cases and 500 000 comments on PostgreSQL (PostgresComments500kPerfIT)",
            "comments-500k-postgresql.md");

    @Override
    void applySeeds(DataSource dataSource) {
        new ResourceDatabasePopulator(
                        new FileSystemResource(Path.of("perf", "seed-cases-postgresql.sql")),
                        new FileSystemResource(Path.of("perf", "seed-comments-postgresql.sql")))
                .execute(dataSource);
    }

    @Override
    List<String> plan(String sql) {
        return jdbc.queryForList("EXPLAIN (ANALYZE, BUFFERS) " + sql, String.class);
    }

    @Override
    PerfReport report() {
        return REPORT;
    }

    @AfterAll
    static void writeReport() throws IOException {
        REPORT.write();
    }
}
