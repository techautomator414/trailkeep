// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static io.trailkeep.importer.ImportVolumeCorpus.CASES;
import static io.trailkeep.importer.ImportVolumeCorpus.EVENTS;
import static io.trailkeep.importer.ImportVolumeCorpus.FILES;
import static io.trailkeep.importer.ImportVolumeCorpus.LOADED;
import static io.trailkeep.importer.ImportVolumeCorpus.NOTES;
import static io.trailkeep.importer.ImportVolumeCorpus.PEOPLE;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.context.event.ApplicationStartedEvent;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The importer at the release volume (WP-30 part b, docs/perf/importer-volume.md): the synthetic legacy tracker of
 * {@code perf/seed-legacy-source-postgresql.sql} ({@link ImportVolumeCorpus}) in a PostgreSQL container of its own,
 * read through a read-only source account and imported end to end as the Job runs it: {@code validate-mapping}, {@code
 * extract}, {@code dry-run}, the sign-off, {@code load}, {@code reconcile} and {@code purge-staging}, each started as
 * the import user in a context of its own. Each command is timed from its context's start to its end (the importer's
 * runner, not the context's start-up), with the heap sampled while it runs and Hibernate's statements, transactions and
 * most frequent queries counted ({@link ImportVolumeReport}). The outcome is asserted at volume: every row staged, the
 * 50 ambiguous people found and nothing FATAL, every case loaded with every note and nothing refused, the run
 * reconciled, and staging empty after the purge. The dry run's issues and figures, and the owners and authors the load
 * chose, are reduced to digests in the report, so a change to how people are matched can be shown to change none of
 * them. The test removes everything it wrote.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractImportVolumePerfIT extends DryRunSupport {

    private static final Logger LOG = LoggerFactory.getLogger(AbstractImportVolumePerfIT.class);

    private static final String PASSWORD = "Tk" + HexFormat.of().formatHex(new SecureRandom().generateSeed(12));

    private static final PostgreSQLContainer LEGACY =
            new PostgreSQLContainer(PostgresTestcontainersConfiguration.IMAGE);

    @Autowired
    private ImportRunRepository runs;

    private long started;
    private long ready;

    @BeforeAll
    static void theLegacyTracker() throws Exception {
        if (LEGACY.isRunning()) {
            return;
        }
        LEGACY.start();
        long begun = System.nanoTime();
        try (Connection owner =
                        DriverManager.getConnection(LEGACY.getJdbcUrl(), LEGACY.getUsername(), LEGACY.getPassword());
                Statement statement = owner.createStatement()) {
            statement.execute(ImportVolumeCorpus.legacySeed());
            // The source account of docs/importer/source-accounts.md, with a password random per run.
            for (String sql : List.of(
                    "CREATE ROLE trailkeep_source LOGIN PASSWORD '" + PASSWORD + "'",
                    "GRANT USAGE ON SCHEMA dbo TO trailkeep_source",
                    "GRANT SELECT ON dbo.users, dbo.cases, dbo.notes, dbo.files, dbo.case_audit TO trailkeep_source",
                    "REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA dbo FROM PUBLIC")) {
                statement.execute(sql);
            }
        }
        LOG.info("Legacy source seeded in {} s", ImportVolumeReport.seconds(System.nanoTime() - begun));
    }

    /**
     * The report for this vendor.
     *
     * @return a new report
     */
    abstract ImportVolumeReport report();

    /** What the load wrote, then the run with its staging, then the users who requested it and the reference data. */
    @AfterEach
    void removeWhatTheImportWrote() {
        ImportVolumeCorpus.removeImported(jdbc);
        removeTheSeededRuns();
        ImportVolumeCorpus.removeTrailkeepSide(jdbc);
    }

    @Test
    void theReleaseVolumeIsImportedEndToEnd(@TempDir Path temp) throws Exception {
        final ImportVolumeReport report = report();
        Path folder = temp.toRealPath();
        Path mapping = ImportVolumeCorpus.files(folder);
        long requester = ImportVolumeCorpus.trailkeepSide(jdbc);
        long run = runs.save(new ImportRun(ImportRun.Kind.IMPORT, ImportVolumeCorpus.SOURCE, requester, Instant.now()))
                .getId();
        removeAfter(run);
        final String area = "--import.content-area=" + Files.createDirectories(folder.resolve("content"));
        final String reports = "--import.reports-dir=" + folder;
        final String external = "--app.attachments.scanning=external";

        timed(report, "validate-mapping", 0, run, mapping, source());
        timed(report, "extract", ImportVolumeCorpus.staged(), run, mapping, source(area));
        assertThat(staged(run)).containsExactly(PEOPLE, CASES, NOTES, FILES, EVENTS);
        assertThat(issues(run)).isEmpty();

        timed(report, "dry-run", ImportVolumeCorpus.staged(), run, mapping, external, area, reports);
        assertThat(count("SELECT COUNT(*) FROM IMPORT_ISSUE WHERE RUN_ID = ? AND SEVERITY = 'FATAL'", run))
                .isZero();
        assertThat(count("SELECT COUNT(*) FROM IMPORT_ISSUE WHERE RUN_ID = ? AND CODE = 'AMBIGUOUS_PERSON'", run))
                .as("persons 2001 to 2050 share an address with two users")
                .isEqualTo(50);
        report.note("Dry-run digest (issues and figures): " + dryRunDigest(run));
        jdbc.update(
                "UPDATE IMPORT_RUN SET APPROVED_BY = REQUESTED_BY, APPROVED_AT = ? WHERE ID = ?",
                Timestamp.from(Instant.now()),
                run);

        timed(report, "load", ImportVolumeCorpus.written(), run, mapping, external, area);
        assertThat(jdbc.queryForList(LOADED, Long.class)).hasSize(CASES);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_COMMENT WHERE CASE_ID IN (" + LOADED + ")", Integer.class))
                .isEqualTo(NOTES);
        assertThat(count("SELECT COUNT(*) FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'LOAD'", run))
                .isZero();
        report.note("Load digest (owners, snapshots and authors): " + loadDigest());

        timed(report, "reconcile", ImportVolumeCorpus.written(), run, mapping, reports);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .isEqualTo("RECONCILED");

        timed(report, "purge-staging", ImportVolumeCorpus.staged(), run, mapping, area);
        assertThat(staged(run)).containsExactly(0, 0, 0, 0, 0);
        report.write();
    }

    /** Runs one command as the Job would, and records its row. */
    private void timed(ImportVolumeReport report, String command, long rows, long run, Path mapping, String... more)
            throws Exception {
        ImportVolumeReport.Heap heap = ImportVolumeReport.Heap.start();
        long begun = System.nanoTime();
        try (ConfigurableApplicationContext context = command(command, run, mapping, more)) {
            long wall = System.nanoTime() - begun;
            report.command(
                    command,
                    rows,
                    ready - started,
                    wall,
                    heap.stop(),
                    context.getBean(EntityManagerFactory.class)
                            .unwrap(SessionFactory.class)
                            .getStatistics());
        }
    }

    /** Times each command's runner, and counts its statements, through the context it starts. */
    @Override
    void configure(SpringApplicationBuilder builder) {
        builder.properties("spring.jpa.properties.hibernate.generate_statistics=true")
                .listeners((ApplicationEvent event) -> {
                    if (event instanceof ApplicationStartedEvent) {
                        started = System.nanoTime();
                    } else if (event instanceof ApplicationReadyEvent) {
                        ready = System.nanoTime();
                    }
                });
    }

    /** The dry run's issues and figures (the forecast day left out), sorted, as one SHA-256. */
    private String dryRunDigest(long run) {
        List<String> lines = new ArrayList<>(
                dryRunIssues(run).stream().map(issue -> String.join(" ", issue)).toList());
        figures(run).stream()
                .filter(line -> !line.startsWith("SLA_FORECAST_DAY "))
                .forEach(lines::add);
        return digest(lines);
    }

    /** Each loaded case's owner and owner snapshot, and each of its comments' author and snapshot, as one SHA-256. */
    private String loadDigest() {
        List<String> lines = new ArrayList<>(jdbc.query(
                "SELECT LEGACY_REF, OWNER_ID, OWNER_NAME_SNAPSHOT FROM CASE_RECORD WHERE LEGACY_REF LIKE 'perf1:%'",
                (row, index) -> row.getString(1) + " " + row.getLong(2) + " " + row.getString(3)));
        lines.addAll(jdbc.query(
                "SELECT c.LEGACY_REF, m.CREATED_AT, m.AUTHOR_ID, m.AUTHOR_NAME_SNAPSHOT FROM CASE_COMMENT m"
                        + " JOIN CASE_RECORD c ON c.ID = m.CASE_ID WHERE c.LEGACY_REF LIKE 'perf1:%'",
                (row, index) -> row.getString(1) + " " + row.getTimestamp(2).toInstant() + " " + row.getString(3) + " "
                        + row.getString(4)));
        return digest(lines);
    }

    private static String digest(List<String> lines) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            lines.stream().sorted().forEach(line -> sha.update((line + "\n").getBytes(StandardCharsets.UTF_8)));
            return HexFormat.of().formatHex(sha.digest()).substring(0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String[] source(String... more) {
        List<String> args = new ArrayList<>(List.of(
                "--import.source.url=" + LEGACY.getJdbcUrl(),
                PlainTcpSources.ARG,
                "--import.source.user=trailkeep_source",
                "--import.source.password=" + PASSWORD));
        args.addAll(List.of(more));
        return args.toArray(String[]::new);
    }
}
