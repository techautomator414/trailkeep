// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.ChildRules.Finding;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedEvent;
import io.trailkeep.importer.staging.StagedValues;
import io.trailkeep.importer.staging.StagingKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The dry run's rules for comments, attachments and legacy history (WP-26 part q2, design §4.3, §7.2, §7.5) on
 * synthetic staged rows: a missing case, an empty required field, a future time, an unknown event kind or field, a
 * plain history value longer than the history keeps (its length the detail), and an attachment over the importer's cap
 * (its size the detail) or without bytes, both skippable only by {@code limits.acceptSkip}.
 */
class ChildRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");

    private static final long CAP = 1000;

    private final ChildRules rules = rules("limits: { maxErrorRate: 0 }");

    @Test
    void soundChildrenHaveNoFinding() {
        assertThat(rules.comment(comment(values -> {}), true)).isEmpty();
        assertThat(rules.attachment(attachment(values -> {}), true)).isEmpty();
        assertThat(rules.attachment(attachment(values -> values.put("size", CAP)), true))
                .as("exactly at the cap")
                .isEmpty();
        assertThat(rules.event(event(values -> {}), true)).isEmpty();
        assertThat(rules.event(event(values -> values.remove("field")), true))
                .as("an event about no field")
                .isEmpty();
        assertThat(rules.accepted()).isEmpty();
    }

    @Test
    void childWhoseCaseWasNotStagedIsAnError() {
        assertThat(codes(rules.comment(comment(values -> {}), false))).containsExactly("PARENT_NOT_STAGED ERROR");
        assertThat(codes(rules.attachment(attachment(values -> {}), false))).containsExactly("PARENT_NOT_STAGED ERROR");
        assertThat(codes(rules.event(event(values -> {}), false))).containsExactly("PARENT_NOT_STAGED ERROR");
    }

    @Test
    void requiredFieldsAndFutureTimesAreErrors() {
        assertThat(codes(rules.comment(new StagedComment(key(), "h", "c1", new StagedValues(Map.of())), true)))
                .containsExactly("WRITTEN_AT_MISSING ERROR", "BODY_MISSING ERROR");
        assertThat(codes(rules.comment(comment(values -> values.put("body", " ")), true)))
                .containsExactly("BODY_MISSING ERROR");
        assertThat(codes(rules.comment(comment(values -> values.put("writtenAt", NOW.plusSeconds(1))), true)))
                .containsExactly("DATE_IN_FUTURE ERROR");
        assertThat(rules.comment(comment(values -> values.put("body", "*".repeat(10_001))), true))
                .as("escaped, 20 002 characters: two comments")
                .containsExactly(new Finding("COMMENT_SPLIT", Severity.WARNING, 2L));
        assertThat(codes(rules.attachment(
                        attachment(values -> {
                            values.remove("fileName");
                            values.put("uploadedAt", NOW.plusSeconds(1));
                        }),
                        true)))
                .containsExactly("FILE_NAME_MISSING ERROR", "DATE_IN_FUTURE ERROR");
        assertThat(codes(rules.event(new StagedEvent(key(), "h", "c1", new StagedValues(Map.of())), true)))
                .containsExactly("AT_MISSING ERROR", "KIND_MISSING ERROR");
        assertThat(codes(rules.event(event(values -> values.put("at", NOW.plusSeconds(1))), true)))
                .containsExactly("DATE_IN_FUTURE ERROR");
    }

    @Test
    void eventKindAndFieldMustBeTheHistoryTables() {
        assertThat(codes(rules.event(
                        event(values -> {
                            values.put("kind", "field_change");
                            values.put("field", "Assignee");
                        }),
                        true)))
                .containsExactly("KIND_INVALID ERROR", "FIELD_INVALID ERROR");
    }

    @Test
    void plainHistoryValueLongerThanTheTableKeepsIsTruncatedWithItsLength() {
        assertThat(rules.event(event(values -> values.put("newValue", "x".repeat(1000))), true))
                .as("exactly what the table keeps")
                .isEmpty();
        assertThat(rules.event(
                        event(values -> {
                            values.put("oldValue", "x".repeat(1001));
                            values.put("newValue", "x".repeat(1200));
                        }),
                        true))
                .containsExactly(new Finding("VALUE_TRUNCATED", Severity.WARNING, 1200L));
        assertThat(rules.event(
                        event(values -> {
                            values.put("field", "description");
                            values.put("newValue", "x".repeat(5000));
                        }),
                        true))
                .as("redacted: only its HMAC and length are kept")
                .isEmpty();
        assertThat(rules.event(
                        event(values -> {
                            values.put("kind", "NOTE");
                            values.put("newValue", "x".repeat(5000));
                        }),
                        true))
                .as("a note is redacted, whatever its field")
                .isEmpty();
    }

    @Test
    void attachmentOverTheCapCarriesItsSizeAndOneWithoutBytesIsMissing() {
        assertThat(rules.attachment(
                        attachment(values -> {
                            values.put("size", CAP + 1);
                            values.remove("sha256");
                        }),
                        true))
                .containsExactly(new Finding("ATTACHMENT_TOO_LARGE", ImportIssue.Severity.ERROR, CAP + 1));
        assertThat(codes(rules.attachment(attachment(values -> values.remove("sha256")), true)))
                .containsExactly("ATTACHMENT_MISSING ERROR");
    }

    @Test
    void acceptSkipTurnsOnlyItsCodesIntoWarnings() {
        StagedAttachment large = attachment(values -> values.put("size", CAP + 1));
        StagedAttachment missing = attachment(values -> values.remove("sha256"));
        ChildRules tooLarge = rules("limits: { maxErrorRate: 0, acceptSkip: [ATTACHMENT_TOO_LARGE] }");
        assertThat(tooLarge.attachment(large, true))
                .containsExactly(new Finding("ATTACHMENT_TOO_LARGE", ImportIssue.Severity.WARNING, CAP + 1));
        assertThat(codes(tooLarge.attachment(missing, true))).containsExactly("ATTACHMENT_MISSING ERROR");
        assertThat(codes(tooLarge.attachment(large, false)))
                .as("a missing case is never skipped")
                .containsExactly("PARENT_NOT_STAGED ERROR", "ATTACHMENT_TOO_LARGE WARNING");
        ChildRules both = rules("limits: { maxErrorRate: 0, acceptSkip: [ATTACHMENT_TOO_LARGE, ATTACHMENT_MISSING] }");
        assertThat(codes(both.attachment(missing, true))).containsExactly("ATTACHMENT_MISSING WARNING");
        assertThat(both.accepted()).containsExactlyInAnyOrder("ATTACHMENT_TOO_LARGE", "ATTACHMENT_MISSING");
    }

    private static List<String> codes(List<Finding> findings) {
        return findings.stream()
                .map(finding -> finding.code() + " " + finding.severity())
                .toList();
    }

    private static ChildRules rules(String mapping) {
        return new ChildRules(YAMLMapper.builder().build().readTree(mapping), CAP, NOW);
    }

    private static StagingKey key() {
        return new StagingKey(1L, "k1");
    }

    private static StagedComment comment(Consumer<Map<String, @Nullable Object>> change) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("writtenAt", Instant.parse("2024-01-16T10:00:00Z"));
        values.put("body", "Synthetic note");
        change.accept(values);
        return new StagedComment(key(), "h", "c1", new StagedValues(values));
    }

    private static StagedAttachment attachment(Consumer<Map<String, @Nullable Object>> change) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("fileName", "synthetic.pdf");
        values.put("uploadedAt", Instant.parse("2024-01-16T10:00:00Z"));
        values.put("size", 10L);
        values.put("sha256", "ab".repeat(32));
        values.put("content", "ab".repeat(32));
        change.accept(values);
        return new StagedAttachment(key(), "h", "c1", new StagedValues(values));
    }

    private static StagedEvent event(Consumer<Map<String, @Nullable Object>> change) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("at", Instant.parse("2024-01-16T10:00:00Z"));
        values.put("kind", "FIELD_CHANGE");
        values.put("field", "status");
        change.accept(values);
        return new StagedEvent(key(), "h", "c1", new StagedValues(values));
    }
}
