// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Lookups read from CSV files under the mapping's directory (WP-26 part l2, design §6.1, §4.3 "Paths"): the header
 * names the key and value columns, the file obeys the mapping's containment and size rules, every problem is a code at
 * the lookup's place with at most a line, and the run's mapping hash covers each lookup file's bytes.
 */
class LookupFilesTest {

    private static final String MAPPING = """
            source: { id: legacy1, kind: jdbc, timezone: UTC }
            lookups:
              status: { "Open": OPEN }
              team: { file: lookups/teams.csv, key: Legacy_Unit, value: trailkeep_team }
            """;

    private static final SecretKey KEY =
            new SecretKeySpec("synthetic-hash-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    @TempDir
    private Path directory;

    private Path mapping;

    @BeforeEach
    void writeTheMapping() throws Exception {
        mapping = Files.writeString(directory.resolve("mapping.yaml"), MAPPING);
        Files.createDirectories(directory.resolve("lookups"));
    }

    @Test
    void fileLookupIsReadByItsHeaderNextToTheInlineOnes() throws Exception {
        teams(String.valueOf((char) 0xFEFF)
                + "legacy_unit,ignored,TRAILKEEP_TEAM\r\nNorth Unit,x,team-north\r\n\"South, Unit\",y,team-south\r\n");

        LookupFiles lookups = read(MAPPING);

        assertThat(lookups.problems()).isEmpty();
        assertThat(lookups.values())
                .containsOnlyKeys("team")
                .containsEntry("team", Map.of("North Unit", "team-north", "South, Unit", "team-south"));
        assertThat(MappingContext.of(yaml(MAPPING), KEY, lookups.values()).lookups())
                .containsEntry("status", Map.of("Open", "OPEN"))
                .containsEntry("team", Map.of("North Unit", "team-north", "South, Unit", "team-south"));
    }

    @Test
    void dialectAndEncodingAreTheMappingsOwn() throws Exception {
        String tsv = MAPPING.replace("teams.csv", "teams.tsv").replace("UTC }", "UTC, encoding: windows-1252 }");
        Files.write(
                directory.resolve("lookups/teams.tsv"),
                "legacy_unit\ttrailkeep_team\nCafé\tteam-a\n".getBytes(Charset.forName("windows-1252")));

        assertThat(read(tsv).values()).containsEntry("team", Map.of("Café", "team-a"));
    }

    @Test
    void unsoundFileIsCodeAtTheLookupsPlace() throws Exception {
        for (String text : List.of(
                "unit,trailkeep_team\nSecret Unit,team-north\n",
                "legacy_unit,trailkeep_team\nSecret Unit,team-north\nSecret Unit,team-other\n",
                "legacy_unit,trailkeep_team\nSecret Unit,team-north\nSouth\n",
                "legacy_unit,trailkeep_team\nSecret Unit,\"open quote\n",
                "")) {
            teams(text);
            assertThat(codes()).as(text).containsExactly("LOOKUP_FILE_INVALID");
        }
        Files.write(directory.resolve("lookups/teams.csv"), new byte[] {'k', ',', 'v', '\n', 'a', (byte) 0x81, '\n'});
        assertThat(codes()).containsExactly("SOURCE_ENCODING");

        Files.write(directory.resolve("lookups/teams.csv"), new byte[MappingValidator.MAX_BYTES + 1]);
        assertThat(codes()).containsExactly("LOOKUP_FILE_TOO_LARGE");

        Files.delete(directory.resolve("lookups/teams.csv"));
        assertThat(codes()).containsExactly("SOURCE_FILE_MISSING");

        Path outside = Files.writeString(
                Files.createTempFile("teams", ".csv"), "legacy_unit,trailkeep_team\nNorth,team-north\n");
        try {
            Files.createSymbolicLink(directory.resolve("lookups/teams.csv"), outside);
            assertThat(codes()).containsExactly("PATH_OUTSIDE_ROOT");
        } finally {
            Files.delete(outside);
        }
    }

    @Test
    void mappingHashCoversEveryLookupFileUnambiguously() throws Exception {
        byte[] content = MAPPING.getBytes(StandardCharsets.UTF_8);
        teams("legacy_unit,trailkeep_team\nNorth,team-north\n");
        String hash = read(MAPPING).hash(content);

        assertThat(read(MAPPING).hash(content)).isEqualTo(hash);
        teams("legacy_unit,trailkeep_team\nNorth,team-south\n");
        assertThat(read(MAPPING).hash(content)).as("an edited lookup file").isNotEqualTo(hash);

        LookupFiles none = read("source: { id: legacy1, kind: jdbc, timezone: UTC }\n");
        assertThat(none.hash("ab".getBytes(StandardCharsets.UTF_8)))
                .as("a plain mapping is hashed with its length too")
                .isNotEqualTo(ValidateMappingCommand.sha256("ab".getBytes(StandardCharsets.UTF_8)));
        teams("c");
        String split = read(MAPPING).hash(new byte[] {'a', 'b'});
        teams("bc");
        assertThat(read(MAPPING).hash(new byte[] {'a'}))
                .as("the parts' lengths keep 'ab'+'c' apart from 'a'+'bc'")
                .isNotEqualTo(split);
    }

    @Test
    void defaultOwnerFileIsReadLikeLookupAndJoinsTheMappingHash() throws Exception {
        String withOwners = MAPPING + "defaults: { owner: { byTeam: lookups/owners.csv } }\n";
        final byte[] content = withOwners.getBytes(StandardCharsets.UTF_8);
        teams("legacy_unit,trailkeep_team\nNorth,team-north\n");
        final Path owners = Files.writeString(
                directory.resolve("lookups/owners.csv"), "Team,owner\nteam-north,owner-1@example.test\n");

        LookupFiles read = read(withOwners);
        assertThat(read.problems()).isEmpty();
        assertThat(read.defaultOwners()).isEqualTo(Map.of("team-north", "owner-1@example.test"));
        assertThat(read.values()).containsOnlyKeys("team");
        String hash = read.hash(content);

        Files.writeString(owners, "team,owner\nteam-north,owner-2@example.test\n");
        assertThat(read(withOwners).hash(content))
                .as("an edited default-owner file")
                .isNotEqualTo(hash);

        Files.writeString(owners, "team,default_owner\nteam-north,owner-2@example.test\n");
        assertThat(read(withOwners).problems())
                .containsExactly(new MappingProblem(MappingProblem.Code.LOOKUP_FILE_INVALID, "/defaults/owner/byTeam"));
        Files.delete(owners);
        assertThat(read(withOwners).problems())
                .containsExactly(new MappingProblem(MappingProblem.Code.SOURCE_FILE_MISSING, "/defaults/owner/byTeam"));
        assertThat(read(MAPPING).defaultOwners()).as("no file named").isEmpty();
    }

    private void teams(String text) throws Exception {
        Files.writeString(directory.resolve("lookups/teams.csv"), text);
    }

    private LookupFiles read(String text) throws Exception {
        Files.writeString(mapping, text);
        return LookupFiles.read(mapping, yaml(text));
    }

    /** The problems' codes, each at the lookup's place: a problem has no room for a value. */
    private List<String> codes() throws Exception {
        List<MappingProblem> problems = read(MAPPING).problems();
        assertThat(problems).extracting(MappingProblem::pointer).containsOnly("/lookups/team/file");
        return problems.stream().map(problem -> problem.code().name()).toList();
    }

    private static JsonNode yaml(String text) {
        return YAMLMapper.builder().build().readTree(text);
    }
}
