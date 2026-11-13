// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/**
 * The two principals that act for the importer (WP-26 part c, docs/design/importer.md §7.3, ADR-0015), read by the
 * import APIs of {@code casefile}, {@code closure} and {@code document} as well. Matching legacy people to users reads
 * any user's identity, so it is the importer's alone: {@link ImportPeopleDirectory}.
 */
public interface ImportPeopleService {

    /** The external id prefix reserved for the principals; no identity provider issues it as a subject. */
    String PRINCIPAL_PREFIX = "urn:trailkeep:principal:";

    /**
     * The "Trailkeep import" principal's id, fixed by {@code V11__import_principals}: negative, so {@code SEQ_APP_USER}
     * never issues it, and outside every sentinel the code uses (the web layer reads a malformed id as {@code -1}).
     * Code finds it through {@link #principals()}; the constant is for the migration's tests.
     */
    long IMPORTER_ID = -1001;

    /** The "Legacy approval (imported)" principal's id, fixed like {@link #IMPORTER_ID}. */
    long LEGACY_APPROVAL_ID = -1002;

    /**
     * The principals' user ids.
     *
     * @param importer "Trailkeep import": creator, owner of unmatched closed cases, uploader, closure requester, holder
     *     of import holds and actor of import audit events
     * @param legacyApproval "Legacy approval (imported)": approver and decider of imported closures, nothing else
     */
    record ImportPrincipals(long importer, long legacyApproval) {}

    /**
     * The two principals, seeded by {@code V11__import_principals}.
     *
     * @return their ids
     * @throws IllegalStateException if either row is missing
     */
    ImportPrincipals principals();
}
