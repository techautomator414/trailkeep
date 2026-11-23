// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
class DefaultImportPeople implements ImportPeopleService {

    static final String IMPORTER = PRINCIPAL_PREFIX + "import";
    static final String LEGACY_APPROVAL = PRINCIPAL_PREFIX + "legacy-approval";

    private final AppUserRepository users;

    /** The principals once read: their rows and ids are fixed by {@code V11__import_principals} and never change. */
    private volatile @Nullable ImportPrincipals principals;

    DefaultImportPeople(AppUserRepository users) {
        this.users = users;
    }

    /**
     * Read once, then kept: every imported case, comment, attachment and history row asks for them, and the load
     * asked the database twice for each of them, 1.2 million queries for the release volume (WP-30 part b,
     * docs/perf/importer-volume.md). A missing row is never kept, so it fails every call until it exists.
     */
    @Override
    public ImportPrincipals principals() {
        ImportPrincipals known = principals;
        if (known == null) {
            known = new ImportPrincipals(principal(IMPORTER), principal(LEGACY_APPROVAL));
            principals = known;
        }
        return known;
    }

    private long principal(String externalId) {
        return users.findByExternalId(externalId)
                .map(user -> Objects.requireNonNull(user.getId()))
                .orElseThrow(() -> new IllegalStateException("Import principal missing: " + externalId));
    }
}
