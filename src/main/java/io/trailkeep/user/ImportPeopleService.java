// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.List;
import java.util.Optional;

/**
 * Read-only people lookups for the importer (WP-26 part c, docs/design/importer.md §7.3): the two principals that act
 * for it (ADR-0015), and matching a legacy person to a Trailkeep user. People are never created here; a user exists
 * only once they have signed in.
 */
public interface ImportPeopleService {

    /** The external id prefix reserved for the principals; no identity provider issues it as a subject. */
    String PRINCIPAL_PREFIX = "urn:trailkeep:principal:";

    /**
     * The principals' user ids.
     *
     * @param importer "Trailkeep import": creator, owner of unmatched closed cases, uploader, closure requester, holder
     *     of import holds and actor of import audit events
     * @param legacyApproval "Legacy approval (imported)": approver and decider of imported closures, nothing else
     */
    record ImportPrincipals(long importer, long legacyApproval) {}

    /**
     * A Trailkeep user a legacy person matched.
     *
     * @param userId the user's id
     * @param displayName the user's name
     * @param disabled whether the user is disabled, which makes them ineligible to own
     */
    record PersonMatch(long userId, String displayName, boolean disabled) {}

    /**
     * The two principals, seeded by {@code V11__import_principals}.
     *
     * @return their ids
     * @throws IllegalStateException if either row is missing
     */
    ImportPrincipals principals();

    /**
     * The user with this external id (the identity provider's subject), exactly as stored. Never a principal.
     *
     * @param externalId the legacy person's external id
     * @return the user, or empty
     */
    Optional<PersonMatch> byExternalId(String externalId);

    /**
     * The users with this email address, compared case-insensitively. Email is not unique in Trailkeep, so more than
     * one match is the importer's {@code AMBIGUOUS_PERSON} issue, never a guess. Never a principal.
     *
     * @param email the legacy person's email address
     * @return the users, by id; empty when none
     */
    List<PersonMatch> byEmail(String email);
}
