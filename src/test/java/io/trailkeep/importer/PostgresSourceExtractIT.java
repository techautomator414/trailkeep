// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * {@code extract} from a PostgreSQL legacy database (WP-26 part n2, design §4.1, §4.3), as the recipe's source account:
 * every entity read in one snapshot, staged exactly as from any other source; the attachment's bytes fetched by its
 * content query into the run's content area under their SHA-256; {@code purge-staging} taking the content area with
 * the rows; and a query the account may not run stopping the extract with its code only.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
class PostgresSourceExtractIT extends ExtractJobSupport {

    @Override
    String vendorProfile() {
        return "test";
    }

    @Test
    void entitiesAndContentAreStagedFromOneSnapshotAndPurgedWithTheirRun(@TempDir Path directory) throws Exception {
        Path mapping = mapping(directory, LegacyPostgresSource.mapping(MAPPING));
        Path area = Files.createDirectories(directory.resolve("content"));
        long run = validatedRun(mapping);

        extract(run, mapping, null, args(area)).close();

        assertThat(staged(run)).containsExactly(2, 3, 2, 1, 0);
        assertThat(issues(run))
                .as("the same rows as from memory, the same issues")
                .containsExactlyInAnyOrder(
                        List.of("CASE", "ERROR", "LOOKUP_MISSING", hash("c3")),
                        List.of("COMMENT", "ERROR", "DUPLICATE_KEY", hash("n2")));
        assertThat(jdbc.queryForObject("SELECT SOURCE_FINGERPRINT FROM IMPORT_RUN WHERE ID = ?", String.class, run))
                .as("the snapshot's hash")
                .matches("postgresql:[0-9a-f]{64}");
        String sha256 = ValidateMappingCommand.sha256(LegacyPostgresSource.SCAN);
        Map<String, @Nullable Object> attachment = jdbc.queryForMap(
                "SELECT CONTENT_POINTER, SIZE_BYTES, SHA256 FROM IMPORT_STG_ATTACHMENT WHERE RUN_ID = ?", run);
        assertThat(attachment)
                .containsEntry("CONTENT_POINTER", sha256)
                .containsEntry("SHA256", sha256)
                .satisfies(row -> assertThat(number(row, "SIZE_BYTES")).isEqualTo(LegacyPostgresSource.SCAN.length));
        Path content = area.resolve(Long.toString(run));
        assertThat(content.resolve(sha256)).hasBinaryContent(LegacyPostgresSource.SCAN);

        start("--import.command=purge-staging", "--import.run=" + run, "--import.content-area=" + area)
                .close();

        assertThat(content).doesNotExist();
        assertThat(staged(run)).containsExactly(0, 0, 0, 0, 0);
    }

    @Test
    void queryTheAccountMayNotRunStopsTheExtractWithItsCode(@TempDir Path directory) throws Exception {
        Path mapping = mapping(
                directory, LegacyPostgresSource.mapping(MAPPING).replace("FROM legacy.notes", "FROM legacy.secrets"));
        long run = validatedRun(mapping);

        assertThatThrownBy(() -> extract(run, mapping, null, args(directory)))
                .hasMessage("Import run " + run + " cannot be extracted: SOURCE_PERMISSION_DENIED");

        assertThat(issues(run))
                .containsExactlyInAnyOrder(
                        List.of("CASE", "ERROR", "LOOKUP_MISSING", hash("c3")),
                        List.of("COMMENT", "FATAL", "SOURCE_PERMISSION_DENIED", ""));
        assertThat(staged(run)).as("persons and cases were staged").containsExactly(2, 3, 0, 0, 0);
    }

    private static String[] args(Path area) throws Exception {
        List<String> args = new ArrayList<>(LegacyPostgresSource.args());
        args.add("--import.content-area=" + area);
        return args.toArray(String[]::new);
    }
}
