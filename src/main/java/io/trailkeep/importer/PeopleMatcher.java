// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.importer.ChildRules.Finding;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.user.ImportPeopleDirectory;
import io.trailkeep.user.ImportPeopleDirectory.PersonMatch;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Function;
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
 *
 * <p>The dry run and the load hold one page of people at a time (WP-30 part b, design §8.2 "Memory"): {@link
 * #prepare} starts a page of staged people, {@link #resolve} a page of the people a page of cases or their children
 * name, read from staging, and either forgets the page before. Once pages are in use, asking about a person the
 * current page did not resolve is a bug, and fails rather than read as unmatched. People matched with {@link #person}
 * alone, outside any page, are all kept.
 */
final class PeopleMatcher {

    private record Eligibility(long userId, long teamId) {}

    private final ImportPeopleDirectory people;
    private final CaseImportService cases;
    private final List<String> order;
    private final Map<String, String> defaultOwners;
    private final Map<String, PersonMatch> matched = new HashMap<>();
    private final Map<String, String> names = new HashMap<>();
    private final Map<String, Identity> identities = new HashMap<>();
    private final Map<Eligibility, Boolean> eligible = new HashMap<>();
    private final Map<Long, Optional<Long>> defaults = new HashMap<>();
    private final SortedSet<Long> openTeams = new TreeSet<>();
    private final Map<String, Optional<PersonMatch>> byExternalId = new HashMap<>();
    private final Map<String, List<PersonMatch>> byEmail = new HashMap<>();
    private final Set<String> resolved = new HashSet<>();
    private boolean paged;
    private final Function<List<String>, List<StagedPerson>> staged;

    /**
     * A matcher with nothing matched yet.
     *
     * @param people the Trailkeep users, read-only
     * @param cases who may own an imported case
     * @param mapping the validated mapping, whose {@code people.match} gives the order
     * @param defaultOwners {@code defaults.owner.byTeam}: team name to the default owner's external id or email
     * @param staged the run's staged people with these keys, for {@link #resolve}
     */
    PeopleMatcher(
            ImportPeopleDirectory people,
            CaseImportService cases,
            JsonNode mapping,
            Map<String, String> defaultOwners,
            Function<List<String>, List<StagedPerson>> staged) {
        this.people = people;
        this.cases = cases;
        this.staged = staged;
        this.order = mapping.at("/people/match")
                .valueStream()
                .map(JsonNode::asString)
                .toList();
        this.defaultOwners = Map.copyOf(defaultOwners);
    }

    /**
     * Looks up, for a page of staged people, every user {@link #person} will ask the directory for, in one query per
     * way of matching and 500 values (#173), in {@code people.match} order: a way is asked only for the people no
     * earlier way matched to exactly one user, as {@link #person} would ask it. The page before is forgotten, its
     * people and their answers.
     *
     * @param page staged people, each then passed to {@link #person}
     */
    void prepare(List<StagedPerson> page) {
        byExternalId.clear();
        byEmail.clear();
        matched.clear();
        names.clear();
        identities.clear();
        resolved.clear();
        paged = true;
        List<StagedPerson> unmatched = page;
        for (String way : order) {
            boolean external = "externalId".equals(way);
            Set<String> values = new HashSet<>();
            for (StagedPerson row : unmatched) {
                String value = external ? row.getExternalId() : row.getEmail();
                if (value != null) {
                    values.add(value);
                }
            }
            if (values.isEmpty()) {
                continue;
            }
            if (external) {
                Map<String, PersonMatch> found = people.byExternalIds(values);
                values.forEach(value -> byExternalId.put(value, Optional.ofNullable(found.get(value))));
            } else {
                Map<String, List<PersonMatch>> found = people.byEmails(values);
                values.forEach(value -> byEmail.put(value, found.getOrDefault(value, List.of())));
            }
            unmatched = unmatched.stream()
                    .filter(row -> found(way, row).size() != 1)
                    .toList();
        }
    }

    /**
     * Starts the dry run's page of one entity (WP-30 part b): a page of people is {@link #prepare prepared}; a page of
     * cases, comments or attachments has the people it names {@link #resolve resolved}: a case's owner, a comment's
     * author, a file's uploader (the dry run asks about nobody a history row names).
     *
     * @param <T> the entity's row type
     * @param type the entity's row type
     * @param rows the page
     */
    <T extends StagedRow> void page(Class<T> type, List<T> rows) {
        if (type == StagedPerson.class) {
            prepare(rows.stream().map(StagedPerson.class::cast).toList());
            return;
        }
        resolve(rows.stream()
                .map(row -> switch (row) {
                    case StagedCase staged -> staged.getOwnerKey();
                    case StagedComment staged -> staged.getAuthorKey();
                    case StagedAttachment staged -> staged.getUploaderKey();
                    default -> null;
                })
                .toList());
    }

    /**
     * Starts a page of the people a page of cases or of their children names: reads them from staging, {@link
     * #prepare prepares} and matches them, so the page's rows can ask about any of them. A key staging does not have
     * is a person who matches nobody and has no name, as before.
     *
     * @param personKeys the keys the page names; {@code null}s are ignored
     */
    void resolve(Collection<@Nullable String> personKeys) {
        List<String> keys =
                personKeys.stream().filter(Objects::nonNull).distinct().toList();
        List<StagedPerson> rows = keys.isEmpty() ? List.of() : staged.apply(keys);
        prepare(rows);
        rows.forEach(this::person);
        resolved.addAll(keys);
    }

    /**
     * Matches one staged person, from what {@link #prepare} looked up for its page or, when it was not prepared, from
     * the directory.
     *
     * @param row the person
     * @return {@code AMBIGUOUS_PERSON} when only an ambiguous email address matched; else nothing
     */
    List<Finding> person(StagedPerson row) {
        resolved.add(row.getKey().legacyKey());
        if (row.getDisplayName() != null) {
            names.put(row.getKey().legacyKey(), row.getDisplayName());
        }
        if (row.getEmail() != null || row.getExternalId() != null) {
            identities.put(row.getKey().legacyKey(), new Identity(row.getEmail(), row.getExternalId()));
        }
        boolean ambiguous = false;
        for (String way : order) {
            List<PersonMatch> found = found(way, row);
            if (found.size() == 1) {
                matched.put(row.getKey().legacyKey(), found.getFirst());
                return List.of();
            }
            ambiguous |= found.size() > 1;
        }
        return ambiguous ? List.of(new Finding("AMBIGUOUS_PERSON", Severity.WARNING, null)) : List.of();
    }

    /** The users one way finds for a person: what {@link #prepare} looked up, else the directory's answer. */
    private List<PersonMatch> found(String way, StagedPerson row) {
        if ("externalId".equals(way)) {
            String externalId = row.getExternalId();
            if (externalId == null) {
                return List.of();
            }
            Optional<PersonMatch> prepared = byExternalId.get(externalId);
            return (prepared != null ? prepared : people.byExternalId(externalId))
                    .stream().toList();
        }
        String email = row.getEmail();
        if (email == null) {
            return List.of();
        }
        List<PersonMatch> prepared = byEmail.get(email);
        return prepared != null ? prepared : people.byEmail(email);
    }

    /**
     * Whether a legacy person, by their canonical key, matched a user.
     *
     * @param personKey the key, or {@code null} when the row names nobody
     * @return true if matched
     */
    boolean matched(@Nullable String personKey) {
        return personKey != null && matched.containsKey(known(personKey));
    }

    /**
     * The user a legacy person matched, as the load names a comment's author (WP-26 part u).
     *
     * @param personKey the person's canonical key, or {@code null} when the row names nobody
     * @return the match, or {@code null}
     */
    @Nullable
    PersonMatch match(@Nullable String personKey) {
        return personKey == null ? null : matched.get(known(personKey));
    }

    /**
     * A legacy person's staged display name.
     *
     * @param personKey the person's canonical key, or {@code null}
     * @return the name, or {@code null} when none was staged
     */
    @Nullable
    String name(@Nullable String personKey) {
        return personKey == null ? null : names.get(known(personKey));
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
        PersonMatch match = match(row.getOwnerKey());
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
        String snapshot = name(key);
        if (!"OPEN".equals(row.getStatus())) {
            return new Owner(importer, snapshot, null);
        }
        Identity legacy = key == null ? null : identities.get(known(key));
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

    /** The key, once checked that the current page resolved it. */
    private String known(String personKey) {
        if (paged && !resolved.contains(personKey)) {
            throw new IllegalStateException("A person was asked about outside the page that resolved the people");
        }
        return personKey;
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
