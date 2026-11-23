// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.repository.Repository;

/** Users. Not case-rooted; module-internal (other modules use the {@code user} services). */
interface AppUserRepository extends Repository<AppUser, Long> {

    AppUser save(AppUser user);

    void flush();

    Optional<AppUser> findById(Long id);

    List<AppUser> findByIdIn(Collection<Long> ids);

    List<AppUser> findAllByOrderByDisplayNameAsc();

    List<AppUser> findByDisabledFalseOrderByDisplayNameAsc();

    Optional<AppUser> findByExternalId(String externalId);

    /** The user with the row locked ({@code FOR UPDATE}) until the transaction ends: sign-ins of one person queue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AppUser> findLockedByExternalId(String externalId);

    List<AppUser> findByEmailIgnoreCase(String email);
}
