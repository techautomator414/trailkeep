// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.importer.staging.StagedValues;
import io.trailkeep.importer.staging.StagingKey;
import io.trailkeep.user.ImportPeopleDirectory;
import io.trailkeep.user.ImportPeopleDirectory.PersonMatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Legacy people matched to synthetic Trailkeep users in {@code people.match} order, an ambiguous email never a guess,
 * an open case's owner kept only when eligible in its team, and each team with open cases checked for an eligible
 * default owner (WP-26 part r2, design §7.3).
 */
class PeopleMatcherTest {

    private static final long NORTH = 10;
    private static final long SOUTH = 20;
    private static final long WEST = 30;

    private final ImportPeopleDirectory people = mock(ImportPeopleDirectory.class);
    private final CaseImportService cases = mock(CaseImportService.class);

    @BeforeEach
    void seedTheUsers() {
        when(people.byExternalId(anyString())).thenReturn(Optional.empty());
        when(people.byEmail(anyString())).thenReturn(List.of());
        when(people.byExternalId("sub-analyst")).thenReturn(Optional.of(user(1)));
        when(people.byEmail("analyst@example.test")).thenReturn(List.of(user(1)));
        when(people.byEmail("shared@example.test")).thenReturn(List.of(user(2), user(3)));
        when(people.byEmail("viewer@example.test")).thenReturn(List.of(user(4)));
        when(people.byEmail("manager@example.test")).thenReturn(List.of(user(5)));
        when(cases.eligibleOwner(1, NORTH)).thenReturn(true);
        when(cases.eligibleOwner(5, NORTH)).thenReturn(true);
        when(cases.eligibleOwner(5, SOUTH)).thenReturn(true);
    }

    @Test
    void peopleMatchInTheMappingsOrderAndAnAmbiguousEmailIsNeverGuessed() {
        PeopleMatcher byEmail = matcher("[email]", Map.of());
        assertThat(byEmail.person(person("p1", "sub-analyst", "nobody@example.test")))
                .as("external id is not a way this mapping matches")
                .isEmpty();
        assertThat(byEmail.matched("p1")).isFalse();
        assertThat(byEmail.person(person("p2", null, "analyst@example.test"))).isEmpty();
        assertThat(byEmail.matched("p2")).isTrue();
        assertThat(byEmail.person(person("p3", null, "shared@example.test")))
                .extracting(ChildRules.Finding::code, ChildRules.Finding::severity)
                .containsExactly(tuple("AMBIGUOUS_PERSON", ImportIssue.Severity.WARNING));
        assertThat(byEmail.matched("p3")).isFalse();
        assertThat(byEmail.matched(null)).isFalse();

        PeopleMatcher both = matcher("[email, externalId]", Map.of());
        assertThat(both.person(person("p3", "sub-analyst", "shared@example.test")))
                .as("an ambiguous address, then a single external id match")
                .isEmpty();
        assertThat(both.matched("p3")).isTrue();
    }

    @Test
    void openCaseKeepsOnlyEligibleOwnerAndEveryTeamWithOpenCasesNeedsEligibleDefault() {
        PeopleMatcher matcher = matcher(
                "[externalId, email]",
                Map.of(
                        "North", "manager@example.test",
                        "South", "viewer@example.test",
                        "Closed Only", "nobody@example.test"));
        matcher.person(person("analyst", "sub-analyst", null));
        matcher.person(person("viewer", null, "viewer@example.test"));

        assertThat(matcher.keepsOwner(staged("OPEN", "analyst"), NORTH)).isTrue();
        assertThat(matcher.keepsOwner(staged("OPEN", "analyst"), SOUTH))
                .as("matched, but may not own the south team's cases")
                .isFalse();
        assertThat(matcher.keepsOwner(staged("OPEN", "viewer"), NORTH)).isFalse();
        assertThat(matcher.keepsOwner(staged("OPEN", "unknown"), WEST)).isFalse();
        assertThat(matcher.keepsOwner(staged("OPEN", "analyst"), null))
                .as("a team Trailkeep does not have")
                .isFalse();
        assertThat(matcher.keepsOwner(staged("CLOSED", "viewer"), 40L))
                .as("a closed case keeps any match, eligible or not")
                .isTrue();
        assertThat(matcher.keepsOwner(staged("CLOSED", "unknown"), 40L)).isFalse();

        assertThat(matcher.teamsWithoutDefaultOwner(
                        Map.of(NORTH, "North", SOUTH, "South", WEST, "West", 40L, "Closed Only")))
                .as("south's default owner may not own its cases; west has none; closed-only needs none")
                .containsExactly(SOUTH, WEST);
    }

    private PeopleMatcher matcher(String order, Map<String, String> defaults) {
        return new PeopleMatcher(
                people, cases, YAMLMapper.builder().build().readTree("people: { match: " + order + " }"), defaults);
    }

    private static PersonMatch user(long id) {
        return new PersonMatch(id, "Synthetic User " + id, false);
    }

    private static StagedPerson person(String key, @Nullable String externalId, @Nullable String email) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("externalId", externalId);
        values.put("email", email);
        return new StagedPerson(new StagingKey(1L, key), "h", new StagedValues(values));
    }

    private static StagedCase staged(String status, String ownerKey) {
        return new StagedCase(
                new StagingKey(1L, "c-" + ownerKey),
                "h",
                new StagedValues(Map.of("status", status, "ownerKey", ownerKey)));
    }
}
