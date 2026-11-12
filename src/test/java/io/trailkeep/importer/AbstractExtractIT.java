// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import javax.crypto.spec.SecretKeySpec;
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
import tools.jackson.databind.JsonNode;

/**
 * {@code extract} as the import user (WP-26 part k1b, design §3 step 2, §4.1, §5), from a source held in memory: no
 * source ships with this row, and every real one (26k2, 26l, 26n2) reaches the extract through the same {@link
 * SourceRows}. This class's own context (the schema owner) migrates, seeds the runs and reads staging back.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractExtractIT {

    private static final byte[] HASH_KEY = "synthetic-import-hash-key-32byte".getBytes(StandardCharsets.UTF_8);

    private static final String MAPPING = """
            version: 1
            source: { id: memory1, kind: files, timezone: UTC }
            limits: { maxErrorRate: 0.0, batchSize: 2 }
            lookups:
              status: { "Open": OPEN, "Closed": CLOSED }
            people: { file: people.jsonl, key: user_id, match: [email], unmatched: snapshot,
                      fields: { displayName: name, email: mail } }
            entities:
              case:
                file: cases.jsonl
                key: case_id
                fields: { title: subject, description: details, caseTypeKey: { const: general-review },
                          teamKey: { const: team-a }, status: { lookup: status, from: state }, openedAt: opened,
                          identifiedOn: found_on, ownerKey: owner }
              comment:
                file: notes.jsonl
                key: note_id
                parent: { entity: case, columns: [case_id] }
                fields: { authorKey: author, writtenAt: created, body: { text: body, format: plain } }
              attachment:
                file: files.jsonl
                key: file_id
                parent: { entity: case, columns: [case_id] }
                fields: { fileName: name, content: { path: path, size: bytes } }
            """;

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

    @Test
    void extractStagesEveryEntityWithItsIssuesAndMovesTheRunOn(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long run = validatedRun(mapping);
        Instant before = Instant.now();

        extract(run, mapping, new MemorySource("memory-fp-1", null)).close();

        assertThat(jdbc.queryForMap(
                        "SELECT STATUS, ROWS_STAGED, SOURCE_FINGERPRINT, STAGING_EXPIRES_AT FROM IMPORT_RUN"
                                + " WHERE ID = ?",
                        run))
                .containsEntry("STATUS", "EXTRACTED")
                .containsEntry("SOURCE_FINGERPRINT", "memory-fp-1")
                .satisfies(row -> assertThat(number(row, "ROWS_STAGED")).isEqualTo(8))
                .satisfies(row -> assertThat(instant(row, "STAGING_EXPIRES_AT"))
                        .as("import.staging-ttl defaults to 14 days")
                        .isBetween(
                                before.plus(Duration.ofDays(14)), Instant.now().plus(Duration.ofDays(14))));
        assertThat(staged(run)).containsExactly(2, 3, 2, 1, 0);
        assertThat(count("SELECT COUNT(*) FROM IMPORT_RUN_CHECKPOINT WHERE RUN_ID = ? AND STAGE = 'EXTRACT'", run))
                .as("every entity, the unmapped event too")
                .isEqualTo(5);
        assertThat(jdbc.queryForMap(
                        "SELECT TITLE, STATUS, OWNER_KEY, OPENED_AT, ROW_HASH FROM IMPORT_STG_CASE"
                                + " WHERE RUN_ID = ? AND LEGACY_KEY = 'c1'",
                        run))
                .containsEntry("TITLE", "Synthetic case one")
                .containsEntry("STATUS", "OPEN")
                .containsEntry("OWNER_KEY", "u1")
                .satisfies(
                        row -> assertThat(instant(row, "OPENED_AT")).isEqualTo(Instant.parse("2019-03-01T09:30:00Z")))
                .satisfies(row -> assertThat((String) row.get("ROW_HASH")).hasSize(64));
        assertThat(jdbc.queryForMap(
                        "SELECT CASE_KEY, CONTENT_POINTER, SIZE_BYTES FROM IMPORT_STG_ATTACHMENT WHERE RUN_ID = ?",
                        run))
                .containsEntry("CASE_KEY", "c1")
                .containsEntry("CONTENT_POINTER", "a/scan.pdf")
                .satisfies(row -> assertThat(number(row, "SIZE_BYTES")).isEqualTo(2048));
        assertThat(issues(run))
                .containsExactlyInAnyOrder(
                        List.of("CASE", "ERROR", "LOOKUP_MISSING", hash("c3")),
                        List.of("COMMENT", "ERROR", "DUPLICATE_KEY", hash("n2")));
    }

    @Test
    void crashedExtractRestartsAtItsFirstUnfinishedEntityOrOverOnAnotherPoint(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long sameRun = validatedRun(mapping);
        long otherRun = validatedRun(mapping);
        for (long run : List.of(sameRun, otherRun)) {
            assertThatThrownBy(() -> extract(run, mapping, new MemorySource("memory-fp-1", StagedEntity.COMMENT)))
                    .hasMessage("planted source failure");
            assertThat(staged(run)).as("one batch of comments was written").containsExactly(2, 3, 2, 0, 0);
        }
        assertThatThrownBy(() -> start(
                        "--import.command=validate-mapping", "--import.run=" + sameRun, "--import.mapping=" + mapping))
                .as("the run is now this mapping and this snapshot")
                .hasMessageContaining("not awaiting a mapping");

        MemorySource same = new MemorySource("memory-fp-1", null);
        extract(sameRun, mapping, same).close();
        MemorySource other = new MemorySource("memory-fp-2", null);
        extract(otherRun, mapping, other).close();

        assertThat(same.read).containsExactly(StagedEntity.COMMENT, StagedEntity.ATTACHMENT);
        assertThat(other.read)
                .containsExactly(StagedEntity.PERSON, StagedEntity.CASE, StagedEntity.COMMENT, StagedEntity.ATTACHMENT);
        for (long run : List.of(sameRun, otherRun)) {
            assertThat(staged(run)).containsExactly(2, 3, 2, 1, 0);
            assertThat(count("SELECT ROWS_STAGED FROM IMPORT_RUN WHERE ID = ? AND STATUS = 'EXTRACTED'", run))
                    .isEqualTo(8);
            assertThat(issues(run)).hasSize(2);
        }
    }

    @Test
    void extractRefusesWhatItCannotStageSoundly(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, MAPPING);
        long changed = validatedRun(mapping);
        Files.writeString(mapping, MAPPING.replace("batchSize: 2", "batchSize: 3"));
        assertThatThrownBy(() -> extract(changed, mapping, new MemorySource("memory-fp-1", null)))
                .hasMessage("The mapping file changed after validate-mapping; validate it again");

        Path elsewhere = mapping(directory, MAPPING.replace("id: memory1", "id: legacy9"));
        long unsupported = validatedRun(elsewhere);
        assertThatThrownBy(() -> extract(unsupported, elsewhere, new MemorySource("memory-fp-1", null)))
                .hasMessage("Import run " + unsupported + " cannot be extracted yet: SOURCE_UNSUPPORTED");
        assertThat(issues(unsupported)).containsExactly(List.of("MAPPING", "FATAL", "SOURCE_UNSUPPORTED", ""));

        Path noCaseKey = mapping(directory, MAPPING.replace("key: case_id", "key: case_ref"));
        long fatal = validatedRun(noCaseKey);
        assertThatThrownBy(() -> extract(fatal, noCaseKey, new MemorySource("memory-fp-1", null)))
                .hasMessage(
                        "Import run " + fatal + " has a FATAL extract issue; fix the mapping and request a new run");
        assertThat(issues(fatal)).contains(List.of("CASE", "FATAL", "COLUMN_MISSING", ""));
        assertThat(staged(fatal))
                .as("nothing of the case entity is checkpointed")
                .containsExactly(2, 0, 0, 0, 0);

        assertThatThrownBy(() ->
                        start("--import.command=extract", "--import.run=" + fatal, "--import.mapping=" + noCaseKey))
                .as("no import.hash-key")
                .hasMessage("import.hash-key must be Base64 of at least 32 bytes to extract");
    }

    private static Path mapping(Path directory, String text) throws Exception {
        Path file = Files.createTempFile(directory, "mapping", ".yaml");
        Files.writeString(file, text);
        return file;
    }

    /** A run that passed {@code validate-mapping} with this file, as the schema owner seeds it. */
    private long validatedRun(Path mapping) throws Exception {
        long run = runs.save(new ImportRun(ImportRun.Kind.IMPORT, "memory1", new CaseSeed(jdbc).user(), Instant.now()))
                .getId();
        jdbc.update(
                "UPDATE IMPORT_RUN SET STATUS = 'VALIDATED', MAPPING_HASH = ? WHERE ID = ?",
                ValidateMappingCommand.sha256(Files.readAllBytes(mapping)),
                run);
        return run;
    }

    private ConfigurableApplicationContext extract(long run, Path mapping, MemorySource source) throws SQLException {
        return start(
                source,
                "--import.command=extract",
                "--import.run=" + run,
                "--import.mapping=" + mapping,
                "--import.hash-key=" + Base64.getEncoder().encodeToString(HASH_KEY));
    }

    private ConfigurableApplicationContext start(String... args) throws SQLException {
        return start(null, args);
    }

    /** Starts the importer as the import user, as its Job would, with the in-memory source when there is one. */
    private ConfigurableApplicationContext start(@Nullable MemorySource source, String... args) throws SQLException {
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
    private List<Integer> staged(long run) {
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

    private int count(String sql, long run) {
        Integer count = jdbc.queryForObject(sql, Integer.class, run);
        return count == null ? 0 : count;
    }

    /** The run's extract issues: entity, severity, code and key hash ("" without one); never a value. */
    private List<List<String>> issues(long run) {
        return jdbc.query(
                "SELECT ENTITY, SEVERITY, CODE, KEY_HASH FROM IMPORT_ISSUE WHERE RUN_ID = ? AND STAGE = 'EXTRACT'",
                (row, index) -> List.of(
                        row.getString(1),
                        row.getString(2),
                        row.getString(3),
                        row.getString(4) == null ? "" : row.getString(4)),
                run);
    }

    private static long number(Map<String, @Nullable Object> row, String column) {
        return ((Number) Objects.requireNonNull(row.get(column), column)).longValue();
    }

    private static Instant instant(Map<String, @Nullable Object> row, String column) {
        return ((Timestamp) Objects.requireNonNull(row.get(column), column)).toInstant();
    }

    private static String hash(String key) {
        return KeyedHash.hex(new SecretKeySpec(HASH_KEY, "HmacSHA256"), key);
    }

    /** A synthetic legacy source held in memory; it can fail on the third row of one entity, after one batch. */
    static final class MemorySource implements SourceRows.Factory, SourceRows {

        private final String fingerprint;
        private final @Nullable StagedEntity failIn;
        private final List<StagedEntity> read = new ArrayList<>();

        MemorySource(String fingerprint, @Nullable StagedEntity failIn) {
            this.fingerprint = fingerprint;
            this.failIn = failIn;
        }

        @Override
        public boolean supports(JsonNode mapping) {
            return "memory1".equals(mapping.at("/source/id").asString(""));
        }

        @Override
        public SourceRows open(JsonNode mapping, Path directory) {
            return this;
        }

        @Override
        public String fingerprint() {
            return fingerprint;
        }

        @Override
        public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
            read.add(entity);
            List<Map<String, Object>> source = switch (entity) {
                case PERSON ->
                    List.of(
                            Map.of("user_id", "u1", "name", "Synthetic One", "mail", "one@example.test"),
                            Map.of("user_id", "u2", "name", "Synthetic Two", "mail", "two@example.test"));
                case CASE -> List.of(legacyCase("c1", "Open"), legacyCase("c2", "Closed"), legacyCase("c3", "Weird"));
                case COMMENT -> List.of(note("n1"), note("n2"), note("n2"));
                case ATTACHMENT ->
                    List.of(Map.of(
                            "file_id", "f1", "case_id", "c1", "name", "scan.pdf", "path", "a/scan.pdf", "bytes", 2048));
                case EVENT -> List.of();
            };
            for (int i = 0; i < source.size(); i++) {
                if (entity == failIn && i == 2) {
                    throw new IllegalStateException("planted source failure");
                }
                rows.accept(new HashMap<>(source.get(i)));
            }
        }

        @Override
        public void close() {
            // Nothing is held open.
        }

        private static Map<String, Object> legacyCase(String id, String state) {
            return Map.of(
                    "case_id", id,
                    "subject", "Synthetic case " + ("c1".equals(id) ? "one" : id),
                    "details", "Synthetic details",
                    "state", state,
                    "opened", "2019-03-01T09:30:00",
                    "found_on", "2019-02-28",
                    "owner", "u1");
        }

        private static Map<String, Object> note(String id) {
            return Map.of(
                    "note_id",
                    id,
                    "case_id",
                    "c1",
                    "author",
                    "u2",
                    "created",
                    "2019-03-02T10:00:00",
                    "body",
                    "Synthetic note");
        }
    }
}
