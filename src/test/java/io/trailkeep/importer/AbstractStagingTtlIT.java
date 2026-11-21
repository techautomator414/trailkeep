// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The staging TTL (WP-26 part j, design §5) and its schedule (#275), each importer started as the import user, as in
 * production: any command first purges every expired run's staging, {@code expire-staging} (the CronJob's command)
 * does only that and needs no run, and two passes never purge one run twice. This class's own context (the schema
 * owner) migrates the schema and seeds the staging.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractStagingTtlIT {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    /** A clamd stream limit below the importer's 25 MB cap (design §4.3 "Size"). */
    private static final String BELOW_THE_CAP = "--import.attachments.clamd-stream-max=1MB";

    @Autowired
    private TestDatabaseUsers users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ImportRunRepository runs;

    @Value("${app.db.import-user}")
    private String importUser = "";

    /**
     * The profile that points this vendor's test contexts at their container ({@code test} or {@code oracle-test}).
     *
     * @return the profile
     */
    abstract String vendorProfile();

    /**
     * The staging TTL and {@code purge-staging} (WP-26 part j, design §5), as the import user: any command first
     * removes the staging of every expired run and records it, leaving runs that have not expired alone; {@code
     * purge-staging} removes one run's staging at once.
     */
    @Test
    void anyCommandPurgesExpiredStagingAndPurgeStagingPurgesItsRun() throws SQLException {
        long expired = stagedRun(Instant.parse("2026-01-01T00:00:00Z"));
        long current = stagedRun(Instant.parse("2099-01-01T00:00:00Z"));

        // A clamd stream limit below the importer's cap stops dry-run and load only, never these commands.
        start("--import.command=status", "--import.run=" + current, BELOW_THE_CAP)
                .close();

        assertThat(stagedRows(expired))
                .as("expired: staging and checkpoints gone")
                .isZero();
        assertThat(stagedRows(current)).as("not expired: untouched").isEqualTo(6);
        assertThat(purgedAt(expired)).isNotNull();
        assertThat(purgedAt(current)).isNull();

        start("--import.command=purge-staging", "--import.run=" + current, BELOW_THE_CAP)
                .close();

        assertThat(stagedRows(current)).isZero();
        assertThat(purgedAt(current)).isNotNull();
    }

    /**
     * The scheduled staging TTL (#275): {@code expire-staging}, the CronJob's command, given no run and with no other
     * importer command run, removes an expired run's staging and content area and records the purge, and leaves a run
     * that has not expired alone.
     */
    @Test
    void expireStagingAloneRemovesExpiredStagingAndKeepsFreshStaging(@TempDir Path area) throws Exception {
        long expired = stagedRun(Instant.parse("2026-01-01T00:00:00Z"));
        long fresh = stagedRun(Instant.parse("2099-01-01T00:00:00Z"));
        Path expiredFolder = Files.createDirectories(area.resolve(Long.toString(expired)));
        Files.writeString(expiredFolder.resolve("ab".repeat(32)), "synthetic content");
        Path freshFile =
                Files.createDirectories(area.resolve(Long.toString(fresh))).resolve("cd".repeat(32));
        Files.writeString(freshFile, "synthetic content");

        start("--import.command=expire-staging", "--import.content-area=" + area)
                .close();

        assertThat(stagedRows(expired))
                .as("expired: staging and checkpoints gone")
                .isZero();
        assertThat(purgedAt(expired)).isNotNull();
        assertThat(expiredFolder).as("expired: content area gone").doesNotExist();
        assertThat(stagedRows(fresh)).as("not expired: untouched").isEqualTo(6);
        assertThat(purgedAt(fresh)).isNull();
        assertThat(freshFile).exists();
    }

    /**
     * One purge per run (#275): while a TTL pass holds an expired run's lock, having purged it, a second pass (the
     * CronJob racing an importer command) waits for it, then finds the run purged and leaves it alone, without failing.
     */
    @Test
    void secondTtlPassWaitsForTheFirstAndLeavesTheRunItPurged() throws Exception {
        long current = stagedRun(Instant.parse("2099-01-01T00:00:00Z"));
        try (ConfigurableApplicationContext importer = start("--import.command=status", "--import.run=" + current)) {
            // Seeded after the start, whose own TTL pass would have taken it.
            long expired = stagedRun(Instant.parse("2026-01-01T00:00:00Z"));
            StagingPurger purger = importer.getBean(StagingPurger.class);
            ExecutorService other = Executors.newSingleThreadExecutor();
            CountDownLatch held = new CountDownLatch(1);
            try {
                Future<List<Long>> second = other.submit(() -> {
                    held.await();
                    return purger.purgeExpired(Instant.now());
                });
                List<Long> first = importer.getBean(TransactionTemplate.class).execute(status -> {
                    List<Long> purged = purger.purgeExpired(Instant.now());
                    held.countDown();
                    assertThatThrownBy(() -> second.get(500, TimeUnit.MILLISECONDS))
                            .as("the second pass waits while the first holds the run")
                            .isInstanceOf(TimeoutException.class);
                    return purged;
                });

                assertThat(first).contains(expired);
                assertThat(second.get(60, TimeUnit.SECONDS))
                        .as("the second pass found the run purged")
                        .doesNotContain(expired);
            } finally {
                other.shutdownNow();
            }
            assertThat(stagedRows(expired)).isZero();
            assertThat(purgedAt(expired)).isNotNull();
        }
    }

    /** A run with one row in every staging table and one checkpoint, as the schema owner seeds it. */
    private long stagedRun(Instant expiresAt) {
        long run = runs.save(new ImportRun(ImportRun.Kind.IMPORT, "legacy1", new CaseSeed(jdbc).user(), NOW))
                .getId();
        jdbc.update("UPDATE IMPORT_RUN SET STAGING_EXPIRES_AT = ? WHERE ID = ?", Timestamp.from(expiresAt), run);
        String hash = "cd".repeat(32);
        jdbc.update("INSERT INTO IMPORT_STG_PERSON (RUN_ID, LEGACY_KEY, ROW_HASH) VALUES (?, 'p1', ?)", run, hash);
        jdbc.update(
                "INSERT INTO IMPORT_STG_CASE (RUN_ID, LEGACY_KEY, ROW_HASH, DESCRIPTION) VALUES (?, 'c1', ?,"
                        + " 'Synthetic staged description')",
                run,
                hash);
        for (String table : List.of("IMPORT_STG_COMMENT", "IMPORT_STG_ATTACHMENT", "IMPORT_STG_EVENT")) {
            // Test-only seeding of a fixed table name from this list.
            jdbc.update(
                    "INSERT INTO " + table + " (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY) VALUES (?, 'x1', ?, 'c1')",
                    run,
                    hash);
        }
        jdbc.update(
                "INSERT INTO IMPORT_RUN_CHECKPOINT (RUN_ID, STAGE, ENTITY, FINISHED_AT) VALUES (?, 'EXTRACT',"
                        + " 'CASE', ?)",
                run,
                Timestamp.from(NOW));
        return run;
    }

    private int stagedRows(long run) {
        int rows = 0;
        for (String table : List.of(
                "IMPORT_STG_PERSON",
                "IMPORT_STG_CASE",
                "IMPORT_STG_COMMENT",
                "IMPORT_STG_ATTACHMENT",
                "IMPORT_STG_EVENT",
                "IMPORT_RUN_CHECKPOINT")) {
            Integer count =
                    jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE RUN_ID = ?", Integer.class, run);
            rows += count == null ? 0 : count;
        }
        return rows;
    }

    private @Nullable Timestamp purgedAt(long run) {
        return jdbc.queryForObject("SELECT STAGING_PURGED_AT FROM IMPORT_RUN WHERE ID = ?", Timestamp.class, run);
    }

    /** The importer as the import user, with Hibernate's {@code validate}; its command runs before this returns. */
    private ConfigurableApplicationContext start(String... args) throws SQLException {
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(vendorProfile(), "importer")
                .properties(
                        "spring.datasource.url=" + users.jdbcUrl(),
                        "spring.datasource.username=" + importUser,
                        "spring.datasource.password=" + users.newPassword(importUser),
                        "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "app.db.schema=" + users.ownerSchema())
                .run(args);
    }
}
