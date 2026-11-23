// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.importer.staging.StagedValues;
import io.trailkeep.importer.staging.StagingKey;
import io.trailkeep.user.ImportPeopleDirectory;
import io.trailkeep.user.ImportPeopleDirectory.PersonMatch;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Batched matching (#173, WP-30 part b) gives exactly what matching one by one gives: the same corpus is matched by a
 * matcher that looks each person up on its own, by one that {@link PeopleMatcher#prepare prepares} each page of 500
 * (the dry run's pages of people), and by one that {@link PeopleMatcher#resolve resolves} from staging the people each
 * page of cases names (the load's pages, in another order, with keys nobody staged), in every {@code people.match}
 * order; every finding, match, name, kept owner, default owner and team without one agrees. The corpus has every case
 * the matcher tells apart: a match by external id; by address, exactly and in other letter case; an address two users
 * share (ambiguous), alone and with an external id that matches; an external id that matches while the address is
 * ambiguous; people who match nobody, with and without values; a disabled user; and an address repeated across pages.
 * The batched matchers ask the directory nothing one by one, at most one query per way and page, and hold one page: a
 * person of an earlier page is refused, never read as unmatched.
 */
class PeopleMatcherBatchTest {

    private static final int PAGE = 500;
    private static final long NORTH = 10;
    private static final long SOUTH = 20;

    @ParameterizedTest
    @ValueSource(strings = {"[externalId, email]", "[email, externalId]", "[email]", "[externalId]"})
    void preparedAndResolvedPagesMatchExactlyAsOneByOne(String order) {
        List<StagedPerson> corpus = corpus();
        Map<String, StagedPerson> byKey = new HashMap<>();
        corpus.forEach(row -> byKey.put(row.getKey().legacyKey(), row));
        PeopleMatcher oneByOne = matcher(new Directory(), order, keys -> List.of());
        List<List<ChildRules.Finding>> expected =
                corpus.stream().map(oneByOne::person).toList();

        Directory batched = new Directory();
        PeopleMatcher prepared = matcher(batched, order, keys -> List.of());
        List<List<ChildRules.Finding>> actual = new ArrayList<>();
        for (List<StagedPerson> page : pages(corpus)) {
            prepared.prepare(page);
            page.forEach(row -> actual.add(prepared.person(row)));
            agree(
                    prepared,
                    oneByOne,
                    page.stream().map(row -> row.getKey().legacyKey()).toList());
        }
        assertThat(actual).isEqualTo(expected);
        assertThat(actual.stream().filter(found -> !found.isEmpty()).count())
                .as("ambiguous: 20 by address only, 20 more whose external id matches when that is a way")
                .isEqualTo(order.contains("email") ? (order.contains("externalId") ? 20L : 40L) : 0L);

        Directory staged = new Directory();
        PeopleMatcher resolved = matcher(
                staged,
                order,
                keys -> keys.stream().map(byKey::get).filter(Objects::nonNull).toList());
        List<String> named = new ArrayList<>(byKey.keySet());
        named.sort(Comparator.reverseOrder());
        named.addAll(List.of("nobody-1", "nobody-2"));
        for (List<String> page : pages(named)) {
            resolved.resolve(page);
            agree(resolved, oneByOne, page);
        }
        assertThatThrownBy(() -> resolved.match(named.getFirst()))
                .as("a person of an earlier page")
                .isInstanceOf(IllegalStateException.class);

        for (PeopleMatcher batch : List.of(prepared, resolved)) {
            assertThat(batch.teamsWithoutDefaultOwner(Map.of(NORTH, "Team 10", SOUTH, "Team 20")))
                    .isEqualTo(oneByOne.teamsWithoutDefaultOwner(Map.of(NORTH, "Team 10", SOUTH, "Team 20")));
        }
        int ways = order.split(",").length;
        for (Directory directory : List.of(batched, staged)) {
            assertThat(directory.singles)
                    .as("a batched matcher asks one by one only for the two default owners")
                    .isEqualTo(directory.singlesForDefaults)
                    .isLessThanOrEqualTo(3);
            assertThat(directory.batches).isLessThanOrEqualTo(ways * ((corpus.size() + PAGE + 1) / PAGE));
        }
    }

    /** Each key's match, name and owner, open and closed, in both teams, as the one-by-one matcher has them. */
    private static void agree(PeopleMatcher batch, PeopleMatcher oneByOne, List<String> keys) {
        for (String key : keys) {
            assertThat(batch.match(key)).as(key).isEqualTo(oneByOne.match(key));
            assertThat(batch.name(key)).as(key).isEqualTo(oneByOne.name(key));
            for (String status : List.of("OPEN", "CLOSED")) {
                for (long team : List.of(NORTH, SOUTH)) {
                    StagedCase owned = owned(status, key);
                    assertThat(batch.owner(owned, team, "Team " + team, 99))
                            .as("%s %s %d", key, status, team)
                            .isEqualTo(oneByOne.owner(owned, team, "Team " + team, 99));
                }
            }
        }
    }

    private static <T> List<List<T>> pages(List<T> all) {
        List<List<T>> pages = new ArrayList<>();
        for (int from = 0; from < all.size(); from += PAGE) {
            pages.add(all.subList(from, Math.min(all.size(), from + PAGE)));
        }
        return pages;
    }

    /**
     * 1 200 people over three pages: users 1 to 300 by external id, 301 to 600 by address in other letter case, 20
     * shared addresses, 20 people with a shared address and a matching external id, the rest matching nobody.
     */
    private static List<StagedPerson> corpus() {
        List<StagedPerson> people = new ArrayList<>();
        for (int p = 1; p <= 1200; p++) {
            String externalId = null;
            String email = null;
            if (p <= 300) {
                externalId = "sub-" + p;
                email = "user" + p + "@example.test";
            } else if (p <= 600) {
                email = "User" + p + "@Example.TEST";
            } else if (p <= 620) {
                email = "shared" + (p % 10) + "@example.test";
            } else if (p <= 640) {
                externalId = "sub-" + (p - 620);
                email = "SHARED" + (p % 10) + "@example.test";
            } else if (p % 3 == 0) {
                externalId = "gone-" + p;
                email = "gone" + p + "@legacy.example.test";
            } else if (p % 3 == 1) {
                email = "user" + (p % 600 + 1) + "@example.test";
            }
            people.add(person(String.format(Locale.ROOT, "p%04d", p), externalId, email));
        }
        return people;
    }

    private static PeopleMatcher matcher(
            Directory directory, String order, Function<List<String>, List<StagedPerson>> staged) {
        CaseImportService cases = mock(CaseImportService.class);
        when(cases.eligibleOwner(anyLong(), anyLong()))
                .thenAnswer(call -> call.<Long>getArgument(0) % 2 == call.<Long>getArgument(1) / 10 % 2);
        return new PeopleMatcher(
                directory,
                cases,
                YAMLMapper.builder().build().readTree("people: { match: " + order + " }"),
                Map.of("Team 10", "sub-2", "Team 20", "user305@example.test"),
                staged);
    }

    private static StagedPerson person(String key, @Nullable String externalId, @Nullable String email) {
        Map<String, @Nullable Object> values = new HashMap<>();
        values.put("externalId", externalId);
        values.put("email", email);
        values.put("displayName", "Synthetic " + key);
        return new StagedPerson(new StagingKey(1L, key), "h", new StagedValues(values));
    }

    private static StagedCase owned(String status, String ownerKey) {
        return new StagedCase(
                new StagingKey(1L, "c-" + ownerKey),
                "h",
                new StagedValues(Map.of("status", status, "ownerKey", ownerKey)));
    }

    /**
     * Users 1 to 600 with external id {@code sub-k} and address {@code userk@example.test}, user 7 disabled, and two
     * users for each of ten shared addresses; the batch lookups answer as the single ones do, case-insensitively by
     * address, and every call is counted.
     */
    private static final class Directory implements ImportPeopleDirectory {

        private record User(long id, String externalId, String email, boolean disabled) {}

        private final List<User> users = new ArrayList<>();
        private int singles;
        private int singlesForDefaults;
        private int batches;

        Directory() {
            for (long k = 1; k <= 600; k++) {
                users.add(new User(k, "sub-" + k, "user" + k + "@example.test", k == 7));
            }
            for (long k = 0; k < 10; k++) {
                users.add(new User(1000 + k, "shared-a-" + k, "shared" + k + "@example.test", false));
                users.add(new User(2000 + k, "shared-b-" + k, "Shared" + k + "@Example.test", false));
            }
        }

        @Override
        public Optional<PersonMatch> byExternalId(String externalId) {
            count(externalId);
            return users.stream()
                    .filter(user -> user.externalId().equals(externalId))
                    .findFirst()
                    .map(Directory::match);
        }

        @Override
        public List<PersonMatch> byEmail(String email) {
            count(email);
            return users.stream()
                    .filter(user -> user.email().equalsIgnoreCase(email))
                    .map(Directory::match)
                    .sorted(Comparator.comparingLong(PersonMatch::userId))
                    .toList();
        }

        @Override
        public Map<String, PersonMatch> byExternalIds(Collection<String> externalIds) {
            batches++;
            Map<String, PersonMatch> found = new HashMap<>();
            for (String externalId : externalIds) {
                users.stream()
                        .filter(user -> user.externalId().equals(externalId))
                        .findFirst()
                        .ifPresent(user -> found.put(externalId, match(user)));
            }
            return found;
        }

        @Override
        public Map<String, List<PersonMatch>> byEmails(Collection<String> emails) {
            batches++;
            Map<String, List<PersonMatch>> found = new HashMap<>();
            for (String email : emails) {
                List<PersonMatch> matches = users.stream()
                        .filter(user -> user.email().equalsIgnoreCase(email))
                        .map(Directory::match)
                        .sorted(Comparator.comparingLong(PersonMatch::userId))
                        .toList();
                if (!matches.isEmpty()) {
                    found.put(email, matches);
                }
            }
            return found;
        }

        @Override
        public List<SignedIn> signedIn() {
            return List.of();
        }

        /** The default owners are named in the mapping and looked up on their own, prepared or not. */
        private void count(String value) {
            singles++;
            if (Objects.equals(value, "sub-2") || Objects.equals(value, "user305@example.test")) {
                singlesForDefaults++;
            }
        }

        private static PersonMatch match(User user) {
            return new PersonMatch(user.id(), "Synthetic User " + user.id(), user.disabled());
        }
    }
}
