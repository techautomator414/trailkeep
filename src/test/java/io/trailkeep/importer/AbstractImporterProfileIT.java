// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.importer.staging.RunCheckpoint;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.persistenceunit.ManagedClassNameFilter;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypes;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;

/**
 * The two persistence units of one image (WP-26 part a, design §2.1 R3), each started as the database user it runs
 * as in production, with Hibernate's {@code validate} on: the {@code importer} profile as the import user, mapping
 * everything, and the web application as the runtime user, without the staging entities. On Oracle this is the test
 * that counts: Hibernate reads the {@code ALL_*} dictionary views there, which list only the objects the connecting
 * user holds a privilege on, so a mapped table or sequence missing from that user's grants fails the start. This
 * class's own context (the schema owner) migrates the schema first and seeds what the applications read.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractImporterProfileIT {

    private static final Instant NOW = Instant.parse("2026-11-10T12:00:00Z");

    @Autowired
    private TestDatabaseUsers users;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ImportRunRepository runs;

    @Autowired
    private ImportIssueRepository issues;

    @Value("${app.db.app-user}")
    private String appUser = "";

    @Value("${app.db.import-user}")
    private String importUser = "";

    /**
     * The profile that points this vendor's test contexts at their container ({@code test} or {@code oracle-test}).
     *
     * @return the profile
     */
    abstract String vendorProfile();

    @Test
    void theImporterStartsAsTheImportUserMapsEverythingAndReportsARunsStatus() throws SQLException {
        long run = runWithIssues();

        try (ConfigurableApplicationContext importer =
                start(importUser, true, "--import.command=status", "--import.run=" + run)) {
            assertThat(importer.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                    .as("no scheduler, so no web job runs as the import user")
                    .isEmpty();
            assertThat(importer.getBeanNamesForType(ClientRegistrationRepository.class))
                    .as("no OIDC client, so the Job needs no client secret")
                    .isEmpty();
            assertThat(importer.getBeanNamesForType(ManagedClassNameFilter.class))
                    .as("the importer maps the staging entities too")
                    .isEmpty();
            assertThat(importer.getBean(PersistenceManagedTypes.class).getManagedClassNames())
                    .contains(ImportRun.class.getName(), ImportIssue.class.getName(), ImportRunSummary.class.getName())
                    .contains(StagedCase.class.getName(), StagedComment.class.getName(), RunCheckpoint.class.getName());

            ImportStatus status = importer.getBean(ImportStatusReader.class).status(run, NOW);

            assertThat(status.kind()).isEqualTo(ImportRun.Kind.IMPORT);
            assertThat(status.sourceId()).isEqualTo("legacy-a");
            assertThat(status.status()).isEqualTo(ImportRun.Status.DRY_RUN);
            assertThat(status.approved()).isFalse();
            assertThat(status.scanMode()).isEqualTo(ImportRun.ScanMode.EXTERNAL);
            assertThat(status.rowsStaged()).isEqualTo(12);
            assertThat(status.issues())
                    .isEqualTo(Map.of(ImportIssue.Severity.ERROR, 1L, ImportIssue.Severity.WARNING, 2L));
            assertThat(status.stagingExpired())
                    .as("expired an hour ago, so the command's TTL pass purged it first")
                    .isFalse();
            assertThat(jdbc.queryForObject(
                            "SELECT COUNT(*) FROM IMPORT_RUN WHERE ID = ? AND STAGING_PURGED_AT IS NOT NULL",
                            Integer.class,
                            run))
                    .isOne();
        }
    }

    @Test
    void theWebApplicationStartsAsTheRuntimeUserWithoutTheStagingEntities() throws SQLException {
        runWithIssues();
        try (ConfigurableApplicationContext web = start(appUser, false)) {
            ManagedClassNameFilter filter = web.getBean(ManagedClassNameFilter.class);

            assertThat(filter.matches(StagingEntityFilter.STAGING_PACKAGE + "ImportStagedCase"))
                    .isFalse();
            assertThat(filter.matches(ImportRun.class.getName())).isTrue();
            assertThat(web.getBean(PersistenceManagedTypes.class).getManagedClassNames())
                    .contains(ImportRun.class.getName())
                    .noneMatch(name -> name.startsWith(StagingEntityFilter.STAGING_PACKAGE));
            assertThat(web.getBeanNamesForType(ImportCommandRunner.class)).isEmpty();
            assertThat(web.getBean(ImportRunService.class).latest())
                    .as("the read API is wired in the web application and the runtime user may SELECT the runs")
                    .isPresent();
        }
    }

    /**
     * The staging TTL and {@code purge-staging} (WP-26 part j, design §5), as the import user: any command first
     * removes the staging of every expired run and records it, leaving runs that have not expired alone; {@code
     * purge-staging} removes one run's staging at once.
     */
    @Test
    void anyCommandPurgesExpiredStagingAndPurgeStagingPurgesItsRun() throws SQLException {
        long expired = stagedRun(Instant.parse("2026-01-01T00:00:00Z"));
        long current = stagedRun(Instant.parse("2099-01-01T00:00:00Z"));

        start(importUser, true, "--import.command=status", "--import.run=" + current)
                .close();

        assertThat(stagedRows(expired))
                .as("expired: staging and checkpoints gone")
                .isZero();
        assertThat(stagedRows(current)).as("not expired: untouched").isEqualTo(6);
        assertThat(purgedAt(expired)).isNotNull();
        assertThat(purgedAt(current)).isNull();

        start(importUser, true, "--import.command=purge-staging", "--import.run=" + current)
                .close();

        assertThat(stagedRows(current)).isZero();
        assertThat(purgedAt(current)).isNotNull();
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

    @Test
    void theImporterRefusesACommandItDoesNotHaveYetAndAStatusWithoutARun() {
        assertThatThrownBy(() -> start(importUser, true, "--import.command=load", "--import.run=1"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Unknown or not yet available import command: load");
        assertThatThrownBy(() -> start(importUser, true, "--import.command=status"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("import.run is required");
    }

    /**
     * {@code validate-mapping} as the import user (WP-26 part b): a sound mapping validates the run with the file's
     * SHA-256; a broken one records a FATAL issue, fails the Job and puts the run back to {@code REQUESTED} with no
     * hash; the corrected file replaces the issue and validates the run again. This proves the import user's grants on
     * both tables. The example's vendor is set to PostgreSQL so its queries are test-fetched from {@link
     * ExampleLegacySource} (26n).
     */
    @Test
    void validateMappingValidatesASoundMappingAndRevokesThatWhenTheFileBreaks(@TempDir Path directory)
            throws Exception {
        long run = runs.save(new ImportRun(ImportRun.Kind.IMPORT, "legacy1", new CaseSeed(jdbc).user(), NOW))
                .getId();
        String example = new ClassPathResource("importer/example-mapping.yaml")
                .getContentAsString(StandardCharsets.UTF_8)
                .replace("vendor: sqlserver", "vendor: postgresql");
        Path mapping = directory.resolve("mapping.yaml");
        String hash = HexFormat.of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(example.getBytes(StandardCharsets.UTF_8)));
        Files.writeString(mapping, example);
        validateMapping(run, mapping).close();
        assertThat(statusAndHash(run)).containsExactly("VALIDATED", hash);

        Files.writeString(mapping, example.replace("FROM dbo.notes", "FROM ${import.source.schema}.notes"));
        assertThatThrownBy(() -> validateMapping(run, mapping))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("The mapping has 1 problems; run " + run + " lists them as issues");
        assertThat(jdbc.queryForList(
                        "SELECT SEVERITY, CODE FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'VALIDATE'", run))
                .singleElement()
                .satisfies(row -> assertThat(row.values()).containsExactly("FATAL", "PLACEHOLDER_NOT_ALLOWED"));
        assertThat(statusAndHash(run)).as("the earlier pass no longer stands").containsExactly("REQUESTED", null);

        Files.writeString(mapping, example);
        validateMapping(run, mapping).close();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'VALIDATE'",
                        Integer.class,
                        run))
                .isZero();
        assertThat(statusAndHash(run)).containsExactly("VALIDATED", hash);
    }

    private List<@Nullable Object> statusAndHash(long run) {
        return new ArrayList<>(jdbc.queryForMap("SELECT STATUS, MAPPING_HASH FROM IMPORT_RUN WHERE ID = ?", run)
                .values());
    }

    private ConfigurableApplicationContext validateMapping(long run, Path mapping) throws SQLException {
        List<String> args = new ArrayList<>(ExampleLegacySource.args());
        args.addAll(List.of("--import.command=validate-mapping", "--import.run=" + run, "--import.mapping=" + mapping));
        return start(importUser, true, args.toArray(String[]::new));
    }

    private long runWithIssues() {
        long requester = new CaseSeed(jdbc).user();
        long run = runs.save(new ImportRun(ImportRun.Kind.IMPORT, "legacy-a", requester, NOW.minusSeconds(86_400)))
                .getId();
        jdbc.update(
                "UPDATE IMPORT_RUN SET STATUS = 'DRY_RUN', SCAN_MODE = 'EXTERNAL', ROWS_STAGED = 12,"
                        + " STAGING_EXPIRES_AT = ? WHERE ID = ?",
                Timestamp.from(NOW.minusSeconds(3_600)),
                run);
        issue(run, ImportIssue.Severity.ERROR, "OWNER_NOT_FOUND", null);
        issue(run, ImportIssue.Severity.WARNING, "NOT_SCANNED", 4_096L);
        issue(run, ImportIssue.Severity.WARNING, "NOT_SCANNED", 512L);
        return run;
    }

    private void issue(long run, ImportIssue.Severity severity, String code, @Nullable Long detail) {
        issues.save(new ImportIssue(
                run,
                ImportIssue.Stage.DRY_RUN,
                ImportIssue.Subject.CASE,
                "ab".repeat(32),
                severity,
                code,
                detail,
                NOW));
    }

    /** Starts the application as {@code user}, as its Deployment or Job would: no migration, {@code validate} on. */
    private ConfigurableApplicationContext start(String user, boolean importer, String... args) throws SQLException {
        List<String> profiles = new ArrayList<>(List.of(vendorProfile()));
        if (importer) {
            profiles.add("importer");
        }
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles.toArray(String[]::new))
                .properties(
                        "spring.datasource.url=" + users.jdbcUrl(),
                        "spring.datasource.username=" + user,
                        "spring.datasource.password=" + users.newPassword(user),
                        "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "app.db.schema=" + users.ownerSchema())
                .run(args);
    }
}
