// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Objects;
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

    private long principal(String externalId) {
        return users.findByExternalId(externalId)
                .map(user -> Objects.requireNonNull(user.getId()))
                .orElseThrow(() -> new IllegalStateException("Import principal missing: " + externalId));
    }
}
