// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.importer.ChildRules.Finding;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.ImportPeopleService.PersonMatch;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * Legacy people matched to Trailkeep users, and the owners the load will give open cases (WP-26 part r2, design §7.3).
 * People are never created. Each staged person is tried in {@code people.match} order, by external id (exactly) and
 * by email address (case-insensitively); the first single match wins. An address that matches more than one user is
 * never a guess: when no other way matches, the person is an {@code AMBIGUOUS_PERSON} WARNING and stays unmatched, a
 * name snapshot like any unmatched person. A disabled user still matches as an author or uploader, never as an owner.
 *
 * <p>An open case keeps its legacy owner only when that owner matched a user who may own the case's team's cases
 * ({@link CaseImportService#eligibleOwner}); otherwise it gets the team's default owner from {@code
 * defaults.owner.byTeam}, a user named by external id or by an email address only one user has, who must be
 * eligible too. Every team with open cases needs one, because an owner eligible today may not be at the load: a team
 * without is a FATAL {@code DEFAULT_OWNER_MISSING} carrying the team's id.
 */
final class PeopleMatcher {

    private record Eligibility(long userId, long teamId) {}

    private final ImportPeopleService people;
    private final CaseImportService cases;
    private final List<String> order;
    private final Map<String, String> defaultOwners;
    private final Map<String, PersonMatch> matched = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Identity> identities = new HashMap<>();
    private final Map<Eligibility, Boolean> eligible = new HashMap<>();
    private final Map<Long, Optional<Long>> defaults = new HashMap<>();
    private final SortedSet<Long> openTeams = new TreeSet<>();

    /**
     * A matcher with nothing matched yet.
     *
     * @param people the Trailkeep users, read-only
     * @param cases who may own an imported case
     * @param mapping the validated mapping, whose {@code people.match} gives the order
     * @param defaultOwners {@code defaults.owner.byTeam}: team name to the default owner's external id or email
     */
    PeopleMatcher(
            ImportPeopleService people, CaseImportService cases, JsonNode mapping, Map<String, String> defaultOwners) {
        this.people = people;
        this.cases = cases;
        this.order = mapping.at("/people/match")
                .valueStream()
                .map(JsonNode::asString)
                .toList();
        this.defaultOwners = Map.copyOf(defaultOwners);
    }

    /**
     * Matches one staged person.
     *
     * @param row the person
     * @return {@code AMBIGUOUS_PERSON} when only an ambiguous email address matched; else nothing
     */
    List<Finding> person(StagedPerson row) {
        if (row.getDisplayName() != null) {
            names.put(row.getKey().legacyKey(), row.getDisplayName());
        }
        if (row.getEmail() != null || row.getExternalId() != null) {
            identities.put(row.getKey().legacyKey(), new Identity(row.getEmail(), row.getExternalId()));
        }
        boolean ambiguous = false;
        for (String way : order) {
            List<PersonMatch> found = switch (way) {
                case "externalId" ->
                    row.getExternalId() == null
                            ? List.of()
                            : people.byExternalId(row.getExternalId()).stream().toList();
                default -> row.getEmail() == null ? List.of() : people.byEmail(row.getEmail());
            };
            if (found.size() == 1) {
                matched.put(row.getKey().legacyKey(), found.getFirst());
                return List.of();
            }
            ambiguous |= found.size() > 1;
        }
        return ambiguous ? List.of(new Finding("AMBIGUOUS_PERSON", Severity.WARNING, null)) : List.of();
    }

    /**
     * Whether a legacy person, by their canonical key, matched a user.
     *
     * @param personKey the key, or {@code null} when the row names nobody
     * @return true if matched
     */
    boolean matched(@Nullable String personKey) {
        return personKey != null && matched.containsKey(personKey);
    }

    /**
     * The user a legacy person matched, as the load names a comment's author (WP-26 part u).
     *
     * @param personKey the person's canonical key, or {@code null} when the row names nobody
     * @return the match, or {@code null}
     */
    @Nullable
    PersonMatch match(@Nullable String personKey) {
        return personKey == null ? null : matched.get(personKey);
    }

    /**
     * A legacy person's staged display name.
     *
     * @param personKey the person's canonical key, or {@code null}
     * @return the name, or {@code null} when none was staged
     */
    @Nullable
    String name(@Nullable String personKey) {
        return personKey == null ? null : names.get(personKey);
    }

    /**
     * Whether a case keeps its legacy owner: for an open case a matched user eligible in its team; for a closed case
     * any match. An open case in a known team that does not keep its owner will get the team's default owner.
     *
     * @param row the case
     * @param teamId its team's id, or {@code null} for a team Trailkeep does not have
     * @return true if the legacy owner is kept
     */
    boolean keepsOwner(StagedCase row, @Nullable Long teamId) {
        if (!"OPEN".equals(row.getStatus())) {
            return matched(row.getOwnerKey());
        }
        if (teamId != null) {
            openTeams.add(teamId);
        }
        PersonMatch match = row.getOwnerKey() == null ? null : matched.get(row.getOwnerKey());
        return match != null && teamId != null && eligible(match.userId(), teamId);
    }

    /**
     * Who owns a case at load (WP-26 part t, design §7.3), and the legacy owner's name when that is someone else.
     *
     * @param userId the owner's {@code APP_USER} id
     * @param nameSnapshot the legacy owner's name as staged when the case does not keep them, or {@code null}
     * @param pending for an open case given its team's default owner, how its legacy owner may be matched later
     *     ({@link ImportOwnerPending}, part z1); else {@code null}
     */
    record Owner(
            long userId,
            @Nullable String nameSnapshot,
            @Nullable Identity pending) {}

    /**
     * A legacy person's email address and external id as staged, at least one of them set.
     *
     * @param email the address, or {@code null}
     * @param externalId the external id, or {@code null}
     */
    record Identity(@Nullable String email, @Nullable String externalId) {}

    /**
     * The owner the load gives a case: its legacy owner when {@link #keepsOwner} keeps them; otherwise, for an open
     * case, its team's eligible default owner, and for a closed case the "Trailkeep import" principal.
     *
     * @param row the case
     * @param teamId its team's id
     * @param teamName its team's name
     * @param importer the import principal's id
     * @return the owner, or {@code null} for an open case whose team has no eligible default owner now
     */
    @Nullable
    Owner owner(StagedCase row, long teamId, String teamName, long importer) {
        String key = row.getOwnerKey();
        if (keepsOwner(row, teamId)) {
            return new Owner(Objects.requireNonNull(matched.get(key), "kept").userId(), null, null);
        }
        String snapshot = key == null ? null : names.get(key);
        if (!"OPEN".equals(row.getStatus())) {
            return new Owner(importer, snapshot, null);
        }
        Identity legacy = key == null ? null : identities.get(key);
        return defaultOwner(teamId, teamName)
                .map(id -> new Owner(id, snapshot, legacy))
                .orElse(null);
    }

    /**
     * The teams with open cases and no eligible default owner.
     *
     * @param teamNames each Trailkeep team's name by id
     * @return their ids, ascending
     */
    List<Long> teamsWithoutDefaultOwner(Map<Long, String> teamNames) {
        return openTeams.stream()
                .filter(team -> defaultOwner(team, teamNames.get(team)).isEmpty())
                .toList();
    }

    private Optional<Long> defaultOwner(long teamId, @Nullable String teamName) {
        return defaults.computeIfAbsent(teamId, team -> {
            String named = teamName == null ? null : defaultOwners.get(teamName);
            if (named == null) {
                return Optional.empty();
            }
            Optional<PersonMatch> user = people.byExternalId(named).or(() -> {
                List<PersonMatch> byEmail = people.byEmail(named);
                return byEmail.size() == 1 ? Optional.of(byEmail.getFirst()) : Optional.empty();
            });
            return user.map(PersonMatch::userId).filter(id -> eligible(id, teamId));
        });
    }

    private boolean eligible(long userId, long teamId) {
        return eligible.computeIfAbsent(
                new Eligibility(userId, teamId), key -> cases.eligibleOwner(key.userId(), key.teamId()));
    }
}
