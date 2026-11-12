// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.RowStager.Staged;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import io.trailkeep.importer.staging.StagedComment;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** One source row to one staging row (WP-26 part k1a, design §5, §6), on a synthetic mapping and synthetic rows. */
class RowStagerTest {

    private static final SecretKey KEY =
            new SecretKeySpec("synthetic-hash-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");

    private static final JsonNode MAPPING = JsonMapper.builder().build().readTree("""
            {"source": {"id": "legacy1", "kind": "files", "timezone": "America/Chicago", "dstAmbiguity": "earlier"},
             "lookups": {"status": {"Open": "OPEN", "Closed": "CLOSED"}},
             "people": {"file": "people.jsonl", "key": "user_id", "keyCase": "insensitive", "match": ["email"],
                        "unmatched": "snapshot", "fields": {"displayName": "name", "email": {"lower": "mail"}}},
             "entities": {
               "case": {"file": "cases.jsonl", "key": ["region", "case_seq"],
                        "fields": {"title": {"trim": "subject"}, "status": {"lookup": "status", "from": "state"},
                                   "openedAt": "opened", "identifiedOn": "found_on", "ownerKey": "assignee",
                                   "legacyAcl": {"when": {"column": "acl", "notBlank": true}, "then": true,
                                                 "else": false},
                                   "legalHold": "hold"}},
               "comment": {"file": "notes.jsonl", "key": "note_id",
                           "parent": {"entity": "case", "columns": ["region", "case_seq"]},
                           "fields": {"authorKey": "author", "writtenAt": {"timestamp": "created"},
                                      "body": {"text": "body", "format": "markdown"}}},
               "attachment": {"file": "files.jsonl", "key": "file_id",
                              "parent": {"entity": "case", "columns": ["region", "case_seq"]},
                              "fields": {"fileName": "name", "content": {"path": "path", "size": "bytes"}}}}}
            """);

    private final MappingContext context = MappingContext.of(MAPPING, KEY, Map.of());

    @Test
    void caseRowIsKeyedTypedAndHashed() {
        Staged staged = stage(StagedEntity.CASE, caseRow());

        assertThat(staged.key()).isEqualTo("north/42");
        assertThat(staged.keyHash()).isEqualTo(KeyedHash.hex(KEY, "north/42"));
        assertThat(staged.caseKey()).isNull();
        assertThat(staged.values())
                .containsEntry("title", "Synthetic title")
                .containsEntry("status", "OPEN")
                .containsEntry("openedAt", Instant.parse("2019-03-01T15:30:00Z"))
                .containsEntry("identifiedOn", LocalDate.of(2019, 2, 28))
                .containsEntry("legacyAcl", true)
                .containsEntry("legalHold", false);
        assertThat(staged.values().get("ownerKey"))
                .as("a person reference is canonicalised as the person's key is: 7.0 is 7, case folded")
                .isEqualTo("7");
        assertThat(staged.rowHash()).hasSize(64);
        assertThat(staged.issues()).isEmpty();
    }

    @Test
    void rowHashIsStableAndFollowsEveryValue() {
        Staged once = stage(StagedEntity.CASE, caseRow());
        Map<String, @Nullable Object> edited = caseRow();
        edited.put("subject", "Synthetic title, edited");

        assertThat(stage(StagedEntity.CASE, caseRow()).rowHash()).isEqualTo(once.rowHash());
        assertThat(stage(StagedEntity.CASE, edited).rowHash()).isNotEqualTo(once.rowHash());
        assertThat(once.rowHash()).doesNotContain("Synthetic").isNotEqualTo(once.keyHash());
    }

    @Test
    void childCarriesItsCaseKeyAndPersonKeysFoldCase() {
        Staged comment = stage(
                StagedEntity.COMMENT,
                Map.of(
                        "note_id",
                        "N-1",
                        "region",
                        "north",
                        "case_seq",
                        42,
                        "author",
                        "ABC",
                        "created",
                        "2019-03-02T10:00:00Z",
                        "body",
                        "**Synthetic** note"));
        Staged person = stage(StagedEntity.PERSON, Map.of("user_id", "AbC", "name", "Synthetic", "mail", "S@X.TEST"));

        assertThat(comment.caseKey()).isEqualTo("north/42");
        assertThat(comment.values())
                .containsEntry("authorKey", "abc")
                .containsEntry("bodyFormat", "markdown")
                .containsEntry("body", "**Synthetic** note");
        assertThat(person.key()).as("keyCase: insensitive").isEqualTo("abc");
        assertThat(person.values()).containsEntry("email", "s@x.test");
        assertThat(new RowStager(context, StagedEntity.COMMENT).row(9, comment))
                .isInstanceOfSatisfying(StagedComment.class, row -> {
                    assertThat(row.getKey().runId()).isEqualTo(9);
                    assertThat(row.getKey().legacyKey()).isEqualTo("N-1");
                    assertThat(row.getRowHash()).isEqualTo(comment.rowHash());
                });
    }

    @Test
    void attachmentStagesItsPointerAndDeclaredSizeOnly() {
        Staged attachment = stage(
                StagedEntity.ATTACHMENT,
                Map.of(
                        "file_id",
                        5,
                        "region",
                        "north",
                        "case_seq",
                        42,
                        "name",
                        "scan.pdf",
                        "path",
                        "a/scan.pdf",
                        "bytes",
                        2048));

        assertThat(attachment.values())
                .containsEntry("fileName", "scan.pdf")
                .containsEntry("content", "a/scan.pdf")
                .containsEntry("size", 2048L);
        assertThat(stage(
                                StagedEntity.ATTACHMENT,
                                Map.of(
                                        "file_id",
                                        6,
                                        "region",
                                        "north",
                                        "case_seq",
                                        42,
                                        "name",
                                        "x",
                                        "path",
                                        "b",
                                        "bytes",
                                        "2048.5"))
                        .issues())
                .containsExactly(error("BAD_NUMBER"));
    }

    @Test
    void rowWithoutUsableKeyIsNotStaged() {
        Map<String, @Nullable Object> noKey = caseRow();
        noKey.put("case_seq", new BigDecimal("4.5"));
        Map<String, @Nullable Object> tooLong = caseRow();
        tooLong.put("region", "r".repeat(90));
        Map<String, @Nullable Object> missingColumn = caseRow();
        missingColumn.remove("region");

        assertThat(stage(StagedEntity.CASE, noKey))
                .satisfies(staged -> assertThat(staged.key()).isNull())
                .satisfies(staged -> assertThat(staged.issues()).containsExactly(error("KEY_INVALID")));
        assertThat(stage(StagedEntity.CASE, tooLong).issues()).containsExactly(error("KEY_TOO_LONG"));
        assertThat(stage(StagedEntity.CASE, missingColumn).issues())
                .containsExactly(new Issue("COLUMN_MISSING", Severity.FATAL));
        assertThat(stage(
                                StagedEntity.COMMENT,
                                Map.of(
                                        "note_id",
                                        "N-2",
                                        "region",
                                        "north",
                                        "case_seq",
                                        "",
                                        "author",
                                        "x",
                                        "created",
                                        "2019-03-02T10:00:00Z",
                                        "body",
                                        "b"))
                        .issues())
                .as("a child whose case key is blank has no case to belong to")
                .containsExactly(error("PARENT_KEY_INVALID"));
    }

    @Test
    void failedFieldIsStagedEmptyWithItsIssue() {
        Map<String, @Nullable Object> row = caseRow();
        row.put("state", "Archived");
        row.put("hold", "maybe");
        row.put("opened", LocalDateTime.of(2019, 11, 3, 1, 30));

        Staged staged = stage(StagedEntity.CASE, row);

        assertThat(staged.key()).isEqualTo("north/42");
        assertThat(staged.values()).containsEntry("status", null).containsEntry("legalHold", null);
        assertThat(staged.values().get("openedAt"))
                .as("the 01:30 overlap of 2019-11-03 in Chicago, placed earlier with a warning")
                .isEqualTo(Instant.parse("2019-11-03T06:30:00Z"));
        assertThat(staged.issues())
                .containsExactlyInAnyOrder(
                        error("LOOKUP_MISSING"), error("BAD_FLAG"), new Issue("DST_AMBIGUOUS", Severity.WARNING));
    }

    private Staged stage(StagedEntity entity, Map<String, ? extends @Nullable Object> row) {
        return new RowStager(context, entity).stage(new HashMap<>(row));
    }

    private static Map<String, @Nullable Object> caseRow() {
        Map<String, @Nullable Object> row = new HashMap<>();
        row.put("region", "north");
        row.put("case_seq", new BigDecimal("42.0"));
        row.put("subject", "  Synthetic title ");
        row.put("state", "Open");
        row.put("opened", "2019-03-01T09:30:00");
        row.put("found_on", "2019-02-28");
        row.put("assignee", new BigDecimal("7.0"));
        row.put("acl", "group-a");
        row.put("hold", "false");
        return row;
    }

    private static Issue error(String code) {
        return new Issue(code, Severity.ERROR);
    }
}
