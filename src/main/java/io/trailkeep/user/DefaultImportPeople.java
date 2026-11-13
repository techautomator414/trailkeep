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
class DefaultImportPeople implements ImportPeopleService {

    static final String IMPORTER = PRINCIPAL_PREFIX + "import";
    static final String LEGACY_APPROVAL = PRINCIPAL_PREFIX + "legacy-approval";

    private final AppUserRepository users;

    DefaultImportPeople(AppUserRepository users) {
        this.users = users;
    }

    @Override
    public ImportPrincipals principals() {
        return new ImportPrincipals(principal(IMPORTER), principal(LEGACY_APPROVAL));
    }

    @Override
    public Optional<PersonMatch> byExternalId(String externalId) {
        return users.findByExternalId(externalId)
                .filter(user -> !user.isPrincipal())
                .map(DefaultImportPeople::match);
    }

    @Override
    public List<PersonMatch> byEmail(String email) {
        return users.findByEmailIgnoreCase(email).stream()
                .filter(user -> !user.isPrincipal())
                .map(DefaultImportPeople::match)
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

    private long principal(String externalId) {
        return users.findByExternalId(externalId)
                .map(user -> Objects.requireNonNull(user.getId()))
                .orElseThrow(() -> new IllegalStateException("Import principal missing: " + externalId));
    }

    private static PersonMatch match(AppUser user) {
        return new PersonMatch(Objects.requireNonNull(user.getId()), user.getDisplayName(), user.isDisabled());
    }
}
