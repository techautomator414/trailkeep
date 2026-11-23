// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import static io.trailkeep.restoredrill.DrillApplication.APP_USER;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.restoredrill.DrillApplication.Observation;
import io.trailkeep.restoredrill.DrillReport.Action;
import io.trailkeep.shared.persistence.DatabaseVendor;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.utility.MountableFile;

/**
 * The restore drill (WP-30 part c, BUILD_PLAN §16 "Backups/DR"), once per vendor, at the release volume: the 500k seed
 * ({@code docs/perf/release-500k.md}: 50 000 cases, 500 000 comments, 500 000 audit events) plus {@value
 * AttachmentSeed#FILES} attachments in the database, as the application stores them.
 *
 * <ol>
 *   <li>A database is migrated by the migration Job's profile as the schema owner, seeded, and used by the application
 *       as the runtime user: it writes audit events with redacted values (under a key generated for this run), and the
 *       search and some downloads are recorded.
 *   <li>Every table is fingerprinted (rows and a digest of every value) and the schema is backed up with the vendor's
 *       own logical backup tool, timed.
 *   <li>The database's container is destroyed. A new, empty one is started and the backup restored into it, timed.
 *   <li>The restored copy must hold the same rows, value for value; its search index must be in the same state; the
 *       migration Job's profile must find nothing to migrate and Flyway's validation must pass; the application must
 *       start as the runtime user, report healthy, find what the search found before, serve the same attachment
 *       bytes, confirm the redacted audit values under the retained key (and not under another), and write new rows;
 *       every attachment's chunks must add up to its recorded size and SHA-256.
 * </ol>
 *
 * <p>The table and the facts go to {@code target/restore-drill/}; {@code docs/ops/restore-drill.md} records a run.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
abstract class AbstractRestoreDrillIT {

    private static final String PURGE_USER = "trailkeep_purge";
    private static final String IMPORT_USER = "trailkeep_import";
    private static final int CANARIES = 10;
    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * The schema owner's login and the schema the tables live in.
     *
     * @param username the owner
     * @param password its password, for this run only
     * @param schema the schema, as the database's metadata spells it
     */
    record Owner(String username, String password, String schema) {}

    /** What the source held and served, for the restored copy to match. */
    private record Before(
            Owner owner,
            Map<String, DatabaseFingerprint.Table> tables,
            Map<String, String> index,
            Map<String, Long> sequences,
            Observation observed) {}

    /** This run's HMAC key, the canary values written under it, and where the backup file is kept between hosts. */
    private record Run(DrillReport report, String hmacKey, List<String> canaries, Path local) {}

    abstract String vendorProfile();

    abstract DatabaseVendor vendor();

    abstract String title();

    abstract String fileName();

    abstract JdbcDatabaseContainer<?> newDatabase();

    abstract Owner newOwner(JdbcDatabaseContainer<?> database, TestDatabaseUsers users) throws SQLException;

    /**
     * Applies the 500k seed as the schema owner, recording its steps.
     *
     * @param database the container
     * @param owner the owner
     * @param connection a connection as the owner
     * @param report the report
     * @throws Exception if seeding fails
     */
    abstract void seed(JdbcDatabaseContainer<?> database, Owner owner, Connection connection, DrillReport report)
            throws Exception;

    /**
     * Where the backup file goes in a container of this vendor.
     *
     * @param database the container
     * @return the path
     * @throws SQLException if the database must be asked
     */
    abstract String dumpPath(JdbcDatabaseContainer<?> database) throws SQLException;

    abstract void backup(JdbcDatabaseContainer<?> database, Owner owner, String path) throws Exception;

    abstract void restore(JdbcDatabaseContainer<?> database, Owner owner, String path, DrillReport report)
            throws Exception;

    /**
     * The search index's state, which must read the same before the backup and after the restore.
     *
     * @param database the container
     * @param owner a connection as the owner
     * @return named facts
     * @throws SQLException if they cannot be read
     */
    abstract Map<String, String> indexState(JdbcDatabaseContainer<?> database, Connection owner) throws SQLException;

    /**
     * Each sequence's high-water mark; after the restore, none may be lower.
     *
     * @param owner a connection as the owner
     * @return the marks by sequence name
     * @throws SQLException if they cannot be read
     */
    abstract Map<String, Long> sequences(Connection owner) throws SQLException;

    /**
     * The space the schema takes in the database, tables, indexes and large objects together.
     *
     * @param owner a connection as the owner
     * @return bytes
     * @throws SQLException if it cannot be read
     */
    abstract long databaseBytes(Connection owner) throws SQLException;

    /** Extra connection properties for this vendor. */
    Properties connectionProperties() {
        return new Properties();
    }

    @Test
    void restoredCopyIsCompleteAndServes() throws Exception {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        List<String> canaries = new ArrayList<>();
        for (int i = 0; i < CANARIES; i++) {
            canaries.add("restore drill canary " + HexFormat.of().formatHex(RANDOM.generateSeed(8)));
        }
        Run run = new Run(
                new DrillReport(title(), fileName()),
                Base64.getEncoder().encodeToString(key),
                canaries,
                Path.of("target", "restore-drill", fileName().replace(".md", ".dump")));
        try {
            Files.createDirectories(run.local().getParent());
            Before before = backUpSource(run);
            run.report().fact("Source database destroyed");
            long rto = restoreAndProve(run, before);
            run.report()
                    .fact("Recovery time measured (copy in, restore, migration Job, application start): " + seconds(rto)
                            + " s");
        } finally {
            Files.deleteIfExists(run.local());
            run.report().write();
        }
    }

    // --- the source --------------------------------------------------------------------------------------------------

    private Before backUpSource(Run run) throws Exception {
        DrillReport report = run.report();
        JdbcDatabaseContainer<?> source = report.time("Source database started", this::newDatabase);
        try {
            TestDatabaseUsers users = users(source);
            Owner owner = newOwner(source, users);
            report.run(
                    "Schema migrated (migration Job profile)",
                    () -> DrillApplication.migrationJob(vendorProfile(), source, owner, users)
                            .close());
            seedSource(source, owner, report);
            Observation observed;
            try (ConfigurableApplicationContext app = report.time(
                    "Application started on the source (runtime user)",
                    () -> DrillApplication.start(vendorProfile(), source, owner, users, run.hmacKey()))) {
                DrillApplication.writeCanaries(app, run.canaries(), 0);
                observed = DrillApplication.observe(app);
            }
            Before before = fingerprint(source, owner, observed, report);
            String path = dumpPath(source);
            long backupMs = report.run("**Backup**", () -> backup(source, owner, path));
            report.fact("Backup: " + mib(DrillDatabase.fileSize(source, path)) + " MiB in " + seconds(backupMs) + " s");
            report.run(
                    "Backup copied off the database host",
                    () -> source.copyFileFromContainer(path, run.local().toString()));
            return before;
        } finally {
            source.stop();
        }
    }

    private void seedSource(JdbcDatabaseContainer<?> source, Owner owner, DrillReport report) throws Exception {
        try (Connection connection = connect(source, owner)) {
            seed(source, owner, connection, report);
            DrillApplication.insertAdmin(connection);
            // What V20 costs an upgrade at this volume (docs/RUNBOOK.md): its index dropped and built again by V20's
            // own
            // statement (Oracle refuses a second index on the same columns), so the schema is unchanged.
            try (Statement statement = connection.createStatement()) {
                statement.execute("DROP INDEX IX_AUDIT_AT");
                report.run(
                        "V20's index built again on the 500 000 audit events",
                        () -> statement.execute("CREATE INDEX IX_AUDIT_AT ON AUDIT_EVENT (AT_TS, ID)"));
            }
            long bytes = report.time(
                    "Attachments written (" + AttachmentSeed.FILES + " files)", () -> AttachmentSeed.write(connection));
            report.fact("Attachments: " + AttachmentSeed.FILES + " files, " + mib(bytes)
                    + " MiB of incompressible content, in 1 MiB chunks");
        }
    }

    private Before fingerprint(JdbcDatabaseContainer<?> source, Owner owner, Observation observed, DrillReport report)
            throws Exception {
        try (Connection connection = connect(source, owner)) {
            Map<String, DatabaseFingerprint.Table> tables = report.time(
                    "Source fingerprinted (every row of every table)",
                    () -> DatabaseFingerprint.of(connection, owner.schema()));
            Before before = new Before(owner, tables, indexState(source, connection), sequences(connection), observed);
            report.fact("Database size: " + mib(databaseBytes(connection)) + " MiB");
            report.fact("Source: " + tables.size() + " tables, "
                    + tables.values().stream()
                            .mapToLong(DatabaseFingerprint.Table::rows)
                            .sum()
                    + " rows; search index " + before.index());
            report.fact("Tables fingerprinted: " + String.join(", ", tables.keySet()));
            return before;
        }
    }

    // --- the restored copy -------------------------------------------------------------------------------------------

    /** Restores into a new database and proves it; returns the milliseconds from copy-in to a serving application. */
    private long restoreAndProve(Run run, Before before) throws Exception {
        DrillReport report = run.report();
        Owner owner = before.owner();
        JdbcDatabaseContainer<?> target = report.time("New empty database started", this::newDatabase);
        try {
            TestDatabaseUsers users = users(target);
            users.createGrantees();
            String path = dumpPath(target);
            long rto = report.run(
                    "Backup copied to the new database host",
                    () -> target.copyFileToContainer(MountableFile.forHostPath(run.local(), 0644), path));
            rto += report.run("**Restore**", () -> restore(target, owner, path, report));
            compare(target, run, before);
            rto += report.run(
                    "Migration Job profile: Flyway validate, nothing pending",
                    validateFlyway(target, owner, users, report));
            rto += serve(target, run, before, users);
            try (Connection runtime = users.login(APP_USER)) {
                AttachmentSeed.Check check = report.time(
                        "Every attachment read back as the runtime user",
                        () -> AttachmentSeed.check(runtime, owner.schema()));
                assertThat(check.problems())
                        .as("attachments whose chunks do not match")
                        .isEmpty();
                assertThat(check.files()).isEqualTo(AttachmentSeed.FILES);
                report.fact("Attachments: all " + check.files() + " files, " + mib(check.bytes())
                        + " MiB, match their recorded size and SHA-256");
            }
            return rto;
        } finally {
            target.stop();
        }
    }

    private void compare(JdbcDatabaseContainer<?> target, Run run, Before before) throws Exception {
        DrillReport report = run.report();
        try (Connection connection = connect(target, before.owner())) {
            Map<String, DatabaseFingerprint.Table> after = report.time(
                    "Restored copy fingerprinted",
                    () -> DatabaseFingerprint.of(connection, before.owner().schema()));
            assertThat(after).as("every table, its row count and every value").isEqualTo(before.tables());
            report.fact("Rows and values: all " + after.size() + " tables match the source, digest for digest");
            Map<String, String> index = indexState(target, connection);
            assertThat(index).as("the search index's state").isEqualTo(before.index());
            report.fact("Search index after the restore: " + index);
            Map<String, Long> sequences = sequences(connection);
            before.sequences()
                    .forEach((name, mark) -> assertThat(sequences.get(name))
                            .as("sequence " + name)
                            .isNotNull()
                            .isGreaterThanOrEqualTo(mark));
            report.fact("Sequences: all " + sequences.size() + " at or above their source marks");
            DrillApplication.verifyCanaries(
                    connection, run.canaries(), Base64.getDecoder().decode(run.hmacKey()));
            report.fact("Audit: the " + CANARIES + " redacted canary values confirm under the retained HMAC key and"
                    + " under no other");
        }
    }

    private Action validateFlyway(
            JdbcDatabaseContainer<?> target, Owner owner, TestDatabaseUsers users, DrillReport report) {
        return () -> {
            try (ConfigurableApplicationContext job =
                    DrillApplication.migrationJob(vendorProfile(), target, owner, users)) {
                Flyway flyway = job.getBean(Flyway.class);
                flyway.validate();
                assertThat(flyway.info().pending()).as("pending migrations").isEmpty();
                report.fact("Flyway: validate passed, " + flyway.info().applied().length + " applied, 0 pending");
            }
        };
    }

    /** Starts the application on the restored copy and checks what it serves; returns the start's milliseconds. */
    private long serve(JdbcDatabaseContainer<?> target, Run run, Before before, TestDatabaseUsers users)
            throws Exception {
        DrillReport report = run.report();
        long started = System.nanoTime();
        try (ConfigurableApplicationContext app = report.time(
                "Application started on the restored copy (runtime user)",
                () -> DrillApplication.start(vendorProfile(), target, before.owner(), users, run.hmacKey()))) {
            final long ms = (System.nanoTime() - started) / 1_000_000;
            Observation now = report.time(
                    "Health, search and downloads through the application", () -> DrillApplication.observe(app));
            assertThat(now).as("what the application serves").isEqualTo(before.observed());
            report.fact("Application: healthy; the search finds the same " + now.searchRows() + " cases; "
                    + DrillApplication.DOWNLOADED.size() + " downloads give the same bytes (SHA-256)");
            DrillApplication.writeCanaries(app, List.of("written after the restore"), CANARIES);
            report.fact("Writes: the application records new audit events on the restored copy");
            return ms;
        }
    }

    // --- the database ------------------------------------------------------------------------------------------------

    TestDatabaseUsers users(JdbcDatabaseContainer<?> database) {
        return new TestDatabaseUsers(vendor(), DrillDatabase.details(database), APP_USER, PURGE_USER, IMPORT_USER);
    }

    Connection connect(JdbcDatabaseContainer<?> database, Owner owner) throws SQLException {
        Properties properties = connectionProperties();
        properties.setProperty("user", owner.username());
        properties.setProperty("password", owner.password());
        return DriverManager.getConnection(database.getJdbcUrl(), properties);
    }

    Connection admin(JdbcDatabaseContainer<?> database) throws SQLException {
        String admin = vendor() == DatabaseVendor.ORACLE ? "system" : database.getUsername();
        return DriverManager.getConnection(database.getJdbcUrl(), admin, database.getPassword());
    }

    static String mib(long bytes) {
        return String.format(Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0));
    }

    static String seconds(long ms) {
        return String.format(Locale.ROOT, "%.1f", ms / 1000.0);
    }
}
