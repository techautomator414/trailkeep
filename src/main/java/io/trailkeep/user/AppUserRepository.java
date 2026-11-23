// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
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

    List<AppUser> findByExternalIdIn(Collection<String> externalIds);

    /**
     * The users whose address, upper-cased by the database as {@link #findByEmailIgnoreCase} compares it, is one of
     * these, each with that upper-cased address, so the caller can tell which of its addresses each user matched.
     *
     * @param emails addresses already in upper case
     * @return pairs of the database's upper-cased address and the user
     */
    @Query("select upper(u.email), u from AppUser u where upper(u.email) in :emails")
    List<Object[]> findByUpperEmailIn(Collection<String> emails);
}
