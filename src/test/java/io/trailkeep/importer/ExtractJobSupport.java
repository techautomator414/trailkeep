// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * What every {@code extract} IT needs (WP-26 part k1b, 26n2): the mapping used throughout, runs seeded as validated by
 * the schema owner and removed after each test with everything they staged, the importer started as the import user
 * as its Job would, and staging read back. The tests are in {@link AbstractExtractIT} and {@link
 * PostgresSourceExtractIT}.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class ExtractJobSupport {

    static final byte[] HASH_KEY = "synthetic-import-hash-key-32byte".getBytes(StandardCharsets.UTF_8);

    static final String MAPPING = """
            version: 1
            source: { id: memory1, kind: files, timezone: UTC, manifest: manifest.yaml }
            limits: { maxErrorRate: 0.0, batchSize: 2 }
            lookups:
              status: { "Open": OPEN, "Closed": CLOSED }
            people: { file: people.mem, key: user_id, match: [email], unmatched: snapshot,
                      fields: { displayName: name, email: mail } }
            entities:
              case:
                file: cases.mem
                key: case_id
                fields: { title: subject, description: details, caseTypeKey: { const: general-review },
                          teamKey: { const: team-a }, status: { lookup: status, from: state }, openedAt: opened,
                          identifiedOn: found_on, ownerKey: owner }
              comment:
                file: notes.mem
                key: note_id
                parent: { entity: case, columns: [case_id] }
                fields: { authorKey: author, writtenAt: created, body: { text: body, format: plain } }
              attachment:
                file: files.mem
                key: file_id
                parent: { entity: case, columns: [case_id] }
                fields: { fileName: name, content: { path: path, size: bytes } }
            """;

    @Autowired
    private TestDatabaseUsers users;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    private ImportRunRepository runs;

    @Value("${app.db.import-user}")
    private String importUser = "";

    /** The runs this test seeded, removed with everything they staged after it. */
    private final List<Long> seeded = new ArrayList<>();

    /**
     * The extract commits from its own processes, so nothing rolls back: each test removes its runs and what they
     * staged, children first, leaving the shared test database as it found it for the other importer ITs.
     */
    @AfterEach
    void removeTheSeededRuns() {
        for (long run : seeded) {
            for (String table : List.of(
                    "IMPORT_STG_EVENT",
                    "IMPORT_STG_ATTACHMENT",
                    "IMPORT_STG_COMMENT",
                    "IMPORT_STG_CASE",
                    "IMPORT_STG_PERSON",
                    "IMPORT_RUN_CHECKPOINT",
                    "IMPORT_ISSUE",
                    "IMPORT_RUN_SUMMARY")) {
                // Test-only cleanup over the fixed table names of this list.
                jdbc.update("DELETE FROM " + table + " WHERE RUN_ID = ?", run);
            }
            jdbc.update("DELETE FROM IMPORT_RUN WHERE ID = ?", run);
        }
        seeded.clear();
    }

    /**
     * The profile that points this vendor's test contexts at their container ({@code test} or {@code oracle-test}).
     *
     * @return the profile
     */
    abstract String vendorProfile();

    static Path mapping(Path directory, String text) throws Exception {
        Path file = Files.createTempFile(directory, "mapping", ".yaml");
        Files.writeString(file, text);
        return file;
    }

    /** A run that passed {@code validate-mapping} with this file and its lookup files, as the schema owner seeds it. */
    long validatedRun(Path mapping) throws Exception {
        long run = runs.save(new ImportRun(ImportRun.Kind.IMPORT, "memory1", new CaseSeed(jdbc).user(), Instant.now()))
                .getId();
        byte[] content = Files.readAllBytes(mapping);
        jdbc.update(
                "UPDATE IMPORT_RUN SET STATUS = 'VALIDATED', MAPPING_HASH = ? WHERE ID = ?",
                LookupFiles.read(mapping, YAMLMapper.builder().build().readTree(content))
                        .hash(content),
                run);
        seeded.add(run);
        return run;
    }

    /** Runs {@code extract} for a run, with the in-memory source when there is one and any further arguments. */
    ConfigurableApplicationContext extract(long run, Path mapping, @Nullable MemorySource source, String... more)
            throws SQLException {
        List<String> args = new ArrayList<>(List.of(
                "--import.command=extract",
                "--import.run=" + run,
                "--import.mapping=" + mapping,
                "--import.hash-key=" + Base64.getEncoder().encodeToString(HASH_KEY)));
        args.addAll(List.of(more));
        return start(source, args.toArray(String[]::new));
    }

    ConfigurableApplicationContext start(String... args) throws SQLException {
        return start(null, args);
    }

    /** Starts the importer as the import user, as its Job would, with the in-memory source when there is one. */
    ConfigurableApplicationContext start(@Nullable MemorySource source, String... args) throws SQLException {
        String user = importUser;
        SpringApplicationBuilder builder = new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.NONE)
                .profiles(vendorProfile(), "importer")
                .properties(
                        "spring.datasource.url=" + users.jdbcUrl(),
                        "spring.datasource.username=" + user,
                        "spring.datasource.password=" + users.newPassword(user),
                        "spring.flyway.enabled=false",
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "app.db.schema=" + users.ownerSchema());
        if (source != null) {
            builder.initializers(context -> context.getBeanFactory().registerSingleton("memorySource", source));
        }
        return builder.run(args);
    }

    /** Staged rows of a run: persons, cases, comments, attachments, events. */
    List<Integer> staged(long run) {
        List<Integer> counts = new ArrayList<>();
        for (String table : List.of(
                "IMPORT_STG_PERSON",
                "IMPORT_STG_CASE",
                "IMPORT_STG_COMMENT",
                "IMPORT_STG_ATTACHMENT",
                "IMPORT_STG_EVENT")) {
            // Test-only counting over the fixed table names of this list.
            counts.add(count("SELECT COUNT(*) FROM " + table + " WHERE RUN_ID = ?", run));
        }
        return counts;
    }

    int count(String sql, long run) {
        Integer count = jdbc.queryForObject(sql, Integer.class, run);
        return count == null ? 0 : count;
    }

    /** The run's extract issues: entity, severity, code and key hash ("" without one); never a value. */
    List<List<String>> issues(long run) {
        return jdbc.query(
                "SELECT ENTITY, SEVERITY, CODE, KEY_HASH FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'EXTRACT'",
                (row, index) -> List.of(
                        row.getString(1),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4) == null ? "" : row.getString(4)),
                run);
    }

    static long number(Map<String, @Nullable Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }

    static Instant instant(Map<String, @Nullable Object> row, String column) {
        return ((Timestamp) Objects.requireNonNull(row.get(column), column)).toInstant();
    }

    static String hash(String key) {
        return KeyedHash.hex(new SecretKeySpec(HASH_KEY, "HmacSHA256"), key);
    }
}
