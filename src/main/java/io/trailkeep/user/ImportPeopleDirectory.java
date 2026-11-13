// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;
import java.util.Optional;

/**
 * The importer's read-only identity lookups (WP-26 parts c and z2, docs/design/importer.md §7.3): matching a legacy
 * person to a Trailkeep user, and every enabled user's email address and external id for {@code relink-owners}. They
 * read any user's identity, so only the {@code importer} module may call them ({@code ImportOnlyApiRulesTest}); the
 * principals other modules need stay on {@link ImportPeopleService}. People are never created here; a user exists only
 * once they have signed in. Never returns a principal.
 */
public interface ImportPeopleDirectory {

    /**
     * A Trailkeep user a legacy person matched.
     *
     * @param userId the user's id
     * @param displayName the user's name
     * @param disabled whether the user is disabled, which makes them ineligible to own
     */
    record PersonMatch(long userId, String displayName, boolean disabled) {}

    /**
     * A user {@code relink-owners} may match a pending owner to (WP-26 part z2, design §7.3).
     *
     * @param userId the user's id
     * @param email their email address, possibly empty
     * @param externalId the identity provider's subject
     */
    record SignedIn(long userId, String email, String externalId) {}

    /**
     * The user with this external id (the identity provider's subject), exactly as stored.
     *
     * @param externalId the legacy person's external id
     * @return the user, or empty
     */
    Optional<PersonMatch> byExternalId(String externalId);

    /**
     * The users with this email address, compared case-insensitively. Email is not unique in Trailkeep, so more than
     * one match is the importer's {@code AMBIGUOUS_PERSON} issue, never a guess.
     *
     * @param email the legacy person's email address
     * @return the users, by id; empty when none
     */
    List<PersonMatch> byEmail(String email);

    /**
     * Every enabled user, whose email address and external id {@code relink-owners} hashes to match the open cases
     * waiting for their legacy owner.
     *
     * @return them, by display name
     */
    List<SignedIn> signedIn();
}
