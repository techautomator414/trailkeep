// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultImportPeopleDirectory implements ImportPeopleDirectory {

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
    public List<SignedIn> signedIn() {
        return users.findByDisabledFalseOrderByDisplayNameAsc().stream()
                .filter(user -> !user.isPrincipal())
                .map(user -> new SignedIn(Objects.requireNonNull(user.getId()), user.getEmail(), user.getExternalId()))
                .toList();
    }

    private static PersonMatch match(AppUser user) {
        return new PersonMatch(Objects.requireNonNull(user.getId()), user.getDisplayName(), user.isDisabled());
    }
}
