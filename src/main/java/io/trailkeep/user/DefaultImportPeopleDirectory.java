// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultImportPeopleDirectory implements ImportPeopleDirectory {

    /** Values per {@code IN} list. */
    private static final int PER_QUERY = 500;

    private final AppUserRepository users;

    DefaultImportPeopleDirectory(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public Optional<PersonMatch> byExternalId(String externalId) {
        return users.findByExternalId(externalId)
                .filter(user -> !user.isPrincipal())
                .map(DefaultImportPeopleDirectory::match);
    }

    @Override
    public List<PersonMatch> byEmail(String email) {
        return users.findByEmailIgnoreCase(email).stream()
                .filter(user -> !user.isPrincipal())
                .map(DefaultImportPeopleDirectory::match)
                .sorted(Comparator.comparingLong(PersonMatch::userId))
                .toList();
    }

    @Override
    public Map<String, PersonMatch> byExternalIds(Collection<String> externalIds) {
        Map<String, PersonMatch> found = new HashMap<>();
        for (List<String> some : chunks(new ArrayList<>(new LinkedHashSet<>(externalIds)))) {
            for (AppUser user : users.findByExternalIdIn(some)) {
                if (!user.isPrincipal()) {
                    found.put(user.getExternalId(), match(user));
                }
            }
        }
        return found;
    }

    @Override
    public Map<String, List<PersonMatch>> byEmails(Collection<String> emails) {
        Map<String, List<PersonMatch>> found = new HashMap<>();
        Map<String, List<String>> asked = new HashMap<>();
        for (String email : new LinkedHashSet<>(emails)) {
            if (StandardCharsets.US_ASCII.newEncoder().canEncode(email)) {
                asked.computeIfAbsent(email.toUpperCase(Locale.ROOT), upper -> new ArrayList<>())
                        .add(email);
            } else {
                List<PersonMatch> one = byEmail(email);
                if (!one.isEmpty()) {
                    found.put(email, one);
                }
            }
        }
        Map<String, List<PersonMatch>> byUpper = new HashMap<>();
        for (List<String> some : chunks(new ArrayList<>(asked.keySet()))) {
            for (Object[] row : users.findByUpperEmailIn(some)) {
                AppUser user = (AppUser) row[1];
                if (!user.isPrincipal()) {
                    byUpper.computeIfAbsent((String) row[0], upper -> new ArrayList<>())
                            .add(match(user));
                }
            }
        }
        byUpper.forEach((upper, matches) -> {
            List<PersonMatch> sorted = matches.stream()
                    .sorted(Comparator.comparingLong(PersonMatch::userId))
                    .toList();
            asked.getOrDefault(upper, List.of()).forEach(email -> found.put(email, sorted));
        });
        return found;
    }

    @Override
    public List<SignedIn> signedIn() {
        return users.findByDisabledFalseOrderByDisplayNameAsc().stream()
                .filter(user -> !user.isPrincipal())
                .map(user -> new SignedIn(Objects.requireNonNull(user.getId()), user.getEmail(), user.getExternalId()))
                .toList();
    }

    /** Lists of at most {@value #PER_QUERY}, well below Oracle's 1000 for an {@code IN} list. */
    private static List<List<String>> chunks(List<String> values) {
        List<List<String>> chunks = new ArrayList<>();
        for (int from = 0; from < values.size(); from += PER_QUERY) {
            chunks.add(values.subList(from, Math.min(values.size(), from + PER_QUERY)));
        }
        return chunks;
    }

    private static PersonMatch match(AppUser user) {
        return new PersonMatch(Objects.requireNonNull(user.getId()), user.getDisplayName(), user.isDisabled());
    }
}
