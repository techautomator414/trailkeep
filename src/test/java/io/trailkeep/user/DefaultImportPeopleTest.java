// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.trailkeep.user.ImportPeopleService.ImportPrincipals;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The import principals are read once and then kept (WP-30 part b): the load asked for them twice for every imported
 * row. A missing principal is never kept, so it fails each call until its row exists.
 */
class DefaultImportPeopleTest {

    private final AppUserRepository users = mock(AppUserRepository.class);
    private final DefaultImportPeople people = new DefaultImportPeople(users);

    @Test
    void thePrincipalsAreReadOnceAndKept() {
        principal(DefaultImportPeople.IMPORTER, ImportPeopleService.IMPORTER_ID);
        principal(DefaultImportPeople.LEGACY_APPROVAL, ImportPeopleService.LEGACY_APPROVAL_ID);

        for (int i = 0; i < 3; i++) {
            assertThat(people.principals())
                    .isEqualTo(new ImportPrincipals(
                            ImportPeopleService.IMPORTER_ID, ImportPeopleService.LEGACY_APPROVAL_ID));
        }

        verify(users, times(1)).findByExternalId(DefaultImportPeople.IMPORTER);
        verify(users, times(1)).findByExternalId(DefaultImportPeople.LEGACY_APPROVAL);
    }

    @Test
    void missingPrincipalFailsEachCallUntilItsRowExists() {
        principal(DefaultImportPeople.IMPORTER, ImportPeopleService.IMPORTER_ID);
        when(users.findByExternalId(DefaultImportPeople.LEGACY_APPROVAL)).thenReturn(Optional.empty());

        assertThatThrownBy(people::principals).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(people::principals).isInstanceOf(IllegalStateException.class);
        principal(DefaultImportPeople.LEGACY_APPROVAL, ImportPeopleService.LEGACY_APPROVAL_ID);

        assertThat(people.principals().legacyApproval()).isEqualTo(ImportPeopleService.LEGACY_APPROVAL_ID);
        verify(users, times(3)).findByExternalId(DefaultImportPeople.LEGACY_APPROVAL);
    }

    private void principal(String externalId, long id) {
        AppUser user = mock(AppUser.class);
        when(user.getId()).thenReturn(id);
        when(users.findByExternalId(externalId)).thenReturn(Optional.of(user));
    }
}
