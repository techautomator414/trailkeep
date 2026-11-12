// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.MappingProblem.Code;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * The structural checks of {@code validate-mapping} (WP-26 part b, design §6 and §10), on the synthetic example mapping
 * and on one change of it per check. Every problem is a code and a place, never a value from the file.
 */
class MappingValidatorTest {

    private static final Path FILE = Path.of("/srv/import/mapping/mapping.yaml");

    private final MappingValidator validator = new MappingValidator();

    @Test
    void theSyntheticExampleMappingIsSound() throws IOException {
        assertThat(validate(example())).isEmpty();
    }

    @Test
    void unknownKeysWrongTypesAndBadSourceIdsFailTheSchema() throws IOException {
        assertThat(validate(example().replace("  batchSize: 500", "  batchSize: 500\n  turbo: true")))
                .extracting(MappingProblem::code)
                .containsOnly(Code.MAPPING_SCHEMA);
        assertThat(validate(example().replace("id: legacy1", "id: Legacy-Tracker-Number-1")))
                .contains(new MappingProblem(Code.MAPPING_SCHEMA, "/source/id"));
        assertThat(validate(example().replace("maxErrorRate: 0.0", "maxErrorRate: lots")))
                .extracting(MappingProblem::code)
                .containsOnly(Code.MAPPING_SCHEMA);
        assertThat(validate(example().replace("      title: {", "      heading: {")))
                .extracting(MappingProblem::code)
                .containsOnly(Code.MAPPING_SCHEMA);
    }

    @Test
    void fileSourceNeedsItsManifest() throws IOException {
        String files = example()
                .replace("  kind: jdbc\n  vendor: sqlserver\n", "  kind: files\n")
                .replace("  url: ${import.source.url}\n  user: ${import.source.user}\n", "");

        assertThat(validate(files)).containsOnly(new MappingProblem(Code.MAPPING_SCHEMA, "/source"));
        assertThat(validate(files.replace("  kind: files\n", "  kind: files\n  manifest: export/manifest.yaml\n")))
                .isEmpty();
    }

    @Test
    void attachmentsReadByPathOrContentIdNeedTheirFolder() throws IOException {
        String byPath = example()
                .replace(
                        "{ query: \"SELECT content FROM dbo.files WHERE file_id = ?\", size: size_bytes }",
                        "{ path: rel }");
        String noFolder = byPath.substring(0, byPath.indexOf("attachments:\n  root:"))
                + byPath.substring(byPath.indexOf("people:\n"));

        assertThat(validate(byPath)).isEmpty();
        assertThat(validate(noFolder)).containsOnly(new MappingProblem(Code.MAPPING_SCHEMA, ""));
        assertThat(validate(noFolder.replace("{ path: rel }", "{ contentId: ref }")))
                .containsOnly(new MappingProblem(Code.MAPPING_SCHEMA, ""));
    }

    @Test
    void contentQueryNamesItsSizeSoAnOverCapFileIsNeverFetched() throws IOException {
        assertThat(validate(example().replace(", size: size_bytes }", " }")))
                .containsOnly(new MappingProblem(Code.MAPPING_SCHEMA, "/entities/attachment/fields/content"));
    }

    /** The design's own security test (§10): a placeholder outside the source block is rejected, never resolved. */
    @Test
    void placeholdersAreAllowedOnlyInUrlAndUserAndOnlyForImportSourceNames() throws IOException {
        String stolen = example()
                .replace(
                        "caseTypeKey: { const: general-review }",
                        "caseTypeKey: { const: \"${spring.datasource.password}\" }");
        assertThat(validate(stolen))
                .containsExactly(
                        new MappingProblem(Code.PLACEHOLDER_NOT_ALLOWED, "/entities/case/fields/caseTypeKey/const"));
        assertThat(validate(example().replace("url: ${import.source.url}", "url: ${spring.datasource.url}")))
                .containsExactly(new MappingProblem(Code.PLACEHOLDER_NOT_ALLOWED, "/source/url"));
        assertThat(validate(example().replace("FROM dbo.notes", "FROM ${import.source.schema}.notes")))
                .containsExactly(new MappingProblem(Code.PLACEHOLDER_NOT_ALLOWED, "/entities/comment/query"));
    }

    /** Each source query goes through the query validator for the source's vendor (WP-26 part m, design §4.1). */
    @Test
    void sourceQueriesMustBeOneAllowListedSelect() throws IOException {
        assertThat(validate(example().replace("FROM dbo.notes", "FROM dbo.notes; DELETE FROM dbo.notes")))
                .containsExactly(new MappingProblem(Code.QUERY_NOT_ONE_STATEMENT, "/entities/comment/query"));
        assertThat(validate(example().replace("SELECT user_id, display_name", "SELECT user_id, xp_cmdshell('dir')")))
                .containsExactly(new MappingProblem(Code.QUERY_FUNCTION_NOT_ALLOWED, "/people/query"));
        assertThat(validate(example().replace("WHERE file_id = ?", "WHERE file_id = ? FOR UPDATE")))
                .extracting(MappingProblem::pointer)
                .containsExactly("/entities/attachment/fields/content/query");
    }

    @Test
    void credentialLookalikesAreRejectedWithoutBeingRepeated() throws IOException {
        List<MappingProblem> problems = validate(example()
                .replace("url: ${import.source.url}", "url: \"jdbc:sqlserver://db.example.invalid;password=hunter2\""));

        assertThat(problems).containsExactly(new MappingProblem(Code.CREDENTIAL_IN_MAPPING, "/source/url"));
        assertThat(problems.toString()).doesNotContain("hunter2", "db.example.invalid");
    }

    @Test
    void filePathsMustStayInsideTheMappingDirectory() throws IOException {
        assertThat(validate(example().replace("file: lookups/teams.csv", "file: ../../etc/teams.csv")))
                .containsExactly(new MappingProblem(Code.PATH_ESCAPES_ROOT, "/lookups/team/file"));
        assertThat(validate(example().replace("byTeam: lookups/default-owners.csv", "byTeam: /etc/owners.csv")))
                .containsExactly(new MappingProblem(Code.PATH_ESCAPES_ROOT, "/defaults/owner/byTeam"));
        assertThat(validate(example().replace("file: lookups/teams.csv", "file: lookups/../lookups/teams.csv")))
                .isEmpty();
    }

    @Test
    void childEntitiesMustNameAsManyParentColumnsAsTheCaseKeyHas() throws IOException {
        String composite = example().replace("    key: case_id\n", "    key: [region, case_seq]\n");

        assertThat(validate(composite))
                .containsExactly(
                        new MappingProblem(Code.KEY_ARITY_MISMATCH, "/entities/attachment/parent/columns"),
                        new MappingProblem(Code.KEY_ARITY_MISMATCH, "/entities/comment/parent/columns"),
                        new MappingProblem(Code.KEY_ARITY_MISMATCH, "/entities/event/parent/columns"));
        assertThat(validate(composite.replace("columns: [case_id]", "columns: [region, case_seq]")))
                .isEmpty();
    }

    @Test
    void fieldsMayUseOnlyDefinedLookupsAndLengthsThatFitTrailkeep() throws IOException {
        assertThat(validate(example().replace("lookup: outcome,", "lookup: verdict,")))
                .containsExactly(
                        new MappingProblem(Code.UNKNOWN_LOOKUP, "/entities/case/fields/closureOutcome/lookup"));
        assertThat(validate(example().replace("maxLength: 200,", "maxLength: 300,")))
                .containsExactly(
                        new MappingProblem(Code.MAX_LENGTH_OVER_TARGET, "/entities/case/fields/title/maxLength"));
    }

    @Test
    void patternsFormatsAndTheZoneMustCompileBeforeAnyExtract() throws IOException {
        assertThat(validate(example()
                        .replace("timezone: America/Chicago", "timezone: America/Atlantis")
                        .replace(
                                "openedAt: { timestamp: opened }",
                                "openedAt: { timestamp: opened, format: \"qq-bb\" }")))
                .containsExactly(
                        new MappingProblem(Code.BAD_FORMAT, "/entities/case/fields/openedAt/format"),
                        new MappingProblem(Code.BAD_TIMEZONE, "/source/timezone"));
    }

    @Test
    void onlySkippableCodesMayBeAcceptedAsSkipped() throws IOException {
        assertThat(validate(example().replace("[ATTACHMENT_TOO_LARGE]", "[ATTACHMENT_TOO_LARGE, OWNER_NOT_FOUND]")))
                .containsExactly(new MappingProblem(Code.ACCEPT_SKIP_NOT_ALLOWED, "/limits/acceptSkip"));
    }

    @Test
    void unreadableOversizedAndNonObjectFilesAreRefusedBeforeAnyOtherCheck() {
        assertThat(validate("version: [1\nsource: {")).containsExactly(new MappingProblem(Code.MAPPING_UNREADABLE, ""));
        assertThat(validate("- just\n- a list\n")).containsExactly(new MappingProblem(Code.MAPPING_UNREADABLE, ""));
        assertThat(validator.validate(FILE, new byte[MappingValidator.MAX_BYTES + 1]))
                .containsExactly(new MappingProblem(Code.MAPPING_TOO_LARGE, ""));
    }

    private List<MappingProblem> validate(String yaml) {
        return validator.validate(FILE, yaml.getBytes(StandardCharsets.UTF_8));
    }

    private static String example() throws IOException {
        return new ClassPathResource("importer/example-mapping.yaml").getContentAsString(StandardCharsets.UTF_8);
    }
}
