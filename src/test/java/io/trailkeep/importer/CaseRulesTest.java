// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseImportService.ImportCaseType;
import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedValues;
import io.trailkeep.importer.staging.StagingKey;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The dry run's case rules (WP-26 part q, design §6.2, §7.1) on synthetic staged cases and reference data: what the
 * load would refuse is an issue code with its severity, and a sound case has none.
 */
class CaseRulesTest {

    private static final Instant NOW = Instant.parse("2026-11-12T15:00:00Z");

    private static final ZoneId ZONE = ZoneId.of("America/Chicago");

    private static final Map<String, Boolean> TEAMS = Map.of("Team North", true, "Team Old", false);

    private static final Map<String, ImportCaseType> TYPES = Map.of(
            "general-review",
                    new ImportCaseType(1, "general-review", true, 7, Set.of("SUBSTANTIATED", "UNSUBSTANTIATED")),
            "retired-type", new ImportCaseType(2, "retired-type", false, 7, Set.of("CLOSED_OTHER")));

    private final CaseRules rules = rules("limits: { maxErrorRate: 0.25 }");

    @Test
    void soundOpenAndClosedCasesHaveNoIssue() {
        assertThat(rules.check(open(values -> {}))).isEmpty();
        assertThat(rules.check(closed(values -> {}))).isEmpty();
        assertThat(rules.check(closed(values -> values.put("caseTypeKey", "retired-type"))).stream()
                        .map(Issue::code)
                        .toList())
                .as("closed history may keep an inactive type, not its outcome list")
                .containsExactly("CLOSURE_OUTCOME_NOT_ALLOWED");
        assertThat(rules.maxErrorRate()).isEqualTo(0.25);
        assertThat(rules("{}").maxErrorRate()).isZero();
    }

    @Test
    void requiredFieldsAreErrors() {
        assertThat(codes(new StagedCase(new StagingKey(1L, "c1"), "h", new StagedValues(Map.of()))))
                .containsExactly(
                        "TITLE_MISSING ERROR",
                        "DESCRIPTION_MISSING ERROR",
                        "TEAM_MISSING ERROR",
                        "CASE_TYPE_MISSING ERROR",
                        "STATUS_MISSING ERROR",
                        "OPENED_AT_MISSING ERROR",
                        "IDENTIFIED_ON_MISSING ERROR");
        assertThat(codes(open(values -> values.put("title", "  ")))).containsExactly("TITLE_MISSING ERROR");
        assertThat(codes(closed(values -> {
                    values.remove("closedAt");
                    values.remove("closureOutcome");
                })))
                .containsExactly("CLOSED_AT_MISSING ERROR", "CLOSURE_OUTCOME_MISSING ERROR");
    }

    @Test
    void closureOutcomeOutsideItsCaseTypesIsFatal() {
        assertThat(codes(closed(values -> values.put("closureOutcome", "CLOSED_OTHER"))))
                .containsExactly("CLOSURE_OUTCOME_NOT_ALLOWED FATAL");
        assertThat(codes(closed(values -> values.put("closureOutcome", "substantiated"))))
                .as("outcomes compare exactly")
                .containsExactly("CLOSURE_OUTCOME_NOT_ALLOWED FATAL");
    }

    @Test
    void referenceDataDecidesTeamAndCaseType() {
        assertThat(codes(open(values -> values.put("teamKey", "Team South")))).containsExactly("TEAM_UNKNOWN FATAL");
        assertThat(codes(open(values -> values.put("teamKey", "Team Old")))).containsExactly("TEAM_INACTIVE ERROR");
        assertThat(codes(open(values -> values.put("caseTypeKey", "other-type"))))
                .containsExactly("CASE_TYPE_UNKNOWN FATAL");
        assertThat(codes(open(values -> values.put("caseTypeKey", "retired-type"))))
                .containsExactly("CASE_TYPE_INACTIVE ERROR");
    }

    @Test
    void statusDatesAndValuesMustBeTrailkeeps() {
        assertThat(codes(open(values -> values.put("status", "PENDING_CLOSURE"))))
                .containsExactly("STATUS_INVALID ERROR");
        assertThat(codes(open(values -> values.put("closedAt", Instant.parse("2024-02-01T00:00:00Z")))))
                .containsExactly("CLOSED_AT_ON_OPEN_CASE ERROR");
        assertThat(codes(closed(values -> values.put("closedAt", Instant.parse("2023-12-31T00:00:00Z")))))
                .containsExactly("CLOSED_BEFORE_OPENED ERROR");
        assertThat(codes(open(values -> values.put("openedAt", NOW.plusSeconds(1)))))
                .containsExactly("DATE_IN_FUTURE ERROR");
        assertThat(codes(open(values -> values.put("identifiedOn", LocalDate.of(2026, 11, 12)))))
                .as("today where the application is")
                .isEmpty();
        assertThat(codes(open(values -> values.put("identifiedOn", LocalDate.of(2026, 11, 13)))))
                .containsExactly("DATE_IN_FUTURE ERROR");
        assertThat(codes(open(values -> {
                    values.put("priority", "URGENT");
                    values.put("sensitivity", "SECRET");
                })))
                .containsExactly("PRIORITY_INVALID ERROR", "SENSITIVITY_INVALID ERROR");
        assertThat(codes(open(values -> {
                    values.put("priority", "HIGH");
                    values.put("sensitivity", "RESTRICTED");
                })))
                .isEmpty();
    }

    @Test
    void holdsAreFatalUntilTheImportHoldExists() {
        assertThat(codes(open(values -> values.put("legalHold", true)))).containsExactly("HOLD_UNAVAILABLE FATAL");
        assertThat(codes(closed(values -> {
                    values.put("openedAt", Instant.parse("2019-01-01T00:00:00Z"));
                    values.put("identifiedOn", LocalDate.of(2019, 1, 1));
                    values.put("closedAt", Instant.parse("2019-11-12T15:00:00Z"));
                })))
                .as("closed exactly its type's seven years ago: the archive job takes it at its next run")
                .containsExactly("HOLD_UNAVAILABLE FATAL");
        assertThat(codes(closed(values -> values.put("closedAt", Instant.parse("2024-06-01T00:00:00Z")))))
                .isEmpty();
    }

    @Test
    void holdReasonAndVisibilityAreWhatTheLoadWouldApply() {
        assertThat(rules.holdReason(open(values -> values.put("legalHold", true))))
                .isEqualTo("LEGAL_HOLD");
        assertThat(rules.holdReason(closed(values -> values.put("closedAt", Instant.parse("2019-11-12T15:00:00Z")))))
                .isEqualTo("PAST_RETENTION");
        assertThat(rules.holdReason(closed(values -> {}))).isNull();
        StagedCase withAcl = open(values -> values.put("legacyAcl", true));
        assertThat(rules.visibility(open(values -> {})))
                .as("Trailkeep's default")
                .isEqualTo("STANDARD");
        assertThat(rules.visibility(withAcl))
                .as("restricted is the default handling")
                .isEqualTo("RESTRICTED");
        assertThat(rules("visibility: { onUnrepresentableAcl: standard }").visibility(withAcl))
                .isEqualTo("STANDARD");
        assertThat(rules.visibility(open(values -> values.put("sensitivity", "RESTRICTED"))))
                .isEqualTo("RESTRICTED");
    }

    @Test
    void legacyAclFailsOnlyWhenTheMappingSaysSo() {
        StagedCase withAcl = open(values -> values.put("legacyAcl", true));
        assertThat(codes(withAcl)).isEmpty();
        assertThat(rules("visibility: { onUnrepresentableAcl: restricted }").check(withAcl))
                .isEmpty();
        assertThat(rules("visibility: { onUnrepresentableAcl: fail }").check(withAcl))
                .extracting(issue -> issue.code() + " " + issue.severity())
                .containsExactly("ACL_UNREPRESENTABLE ERROR");
    }

    private List<String> codes(StagedCase row) {
        return rules.check(row).stream()
                .map(issue -> issue.code() + " " + issue.severity())
                .toList();
    }

    private static CaseRules rules(String mapping) {
        JsonNode tree = YAMLMapper.builder().build().readTree(mapping);
        return new CaseRules(tree, TEAMS, TYPES, NOW, ZONE);
    }

    private static StagedCase open(Consumer<Map<String, @Nullable Object>> change) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("title", "Synthetic case");
        values.put("description", "Synthetic description");
        values.put("teamKey", "Team North");
        values.put("caseTypeKey", "general-review");
        values.put("status", "OPEN");
        values.put("openedAt", Instant.parse("2024-01-15T10:00:00Z"));
        values.put("identifiedOn", LocalDate.of(2024, 1, 14));
        change.accept(values);
        return new StagedCase(new StagingKey(1L, "c1"), "h", new StagedValues(values));
    }

    private static StagedCase closed(Consumer<Map<String, @Nullable Object>> change) {
        return open(values -> {
            values.put("status", "CLOSED");
            values.put("closedAt", Instant.parse("2024-03-01T10:00:00Z"));
            values.put("closureOutcome", "SUBSTANTIATED");
            change.accept(values);
        });
    }
}
