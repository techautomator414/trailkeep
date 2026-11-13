// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;

/**
 * An open case the load gave its team's default owner because its legacy owner matched no user who may own it (V18
 * {@code IMPORT_OWNER_PENDING}, design §7.3), saved in the case's transaction for {@code relink-owners}. It holds no
 * legacy value: only keyed hashes of the legacy owner's normalised email address and external id, which {@link
 * #emailHmac} and {@link #externalIdHmac} compute for a Trailkeep user's in the same way, so nothing outlives staging
 * in the clear. Each hash is taken over a prefixed text, so neither can equal the other or a key hash of an issue.
 */
@Entity
@Table(name = "IMPORT_OWNER_PENDING")
class ImportOwnerPending {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "importOwnerPendingSeq")
    @SequenceGenerator(name = "importOwnerPendingSeq", sequenceName = "SEQ_IMPORT_OWNER_PENDING", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "CASE_ID", nullable = false, updatable = false)
    private long caseId;

    @Column(name = "DEFAULT_OWNER_ID", nullable = false, updatable = false)
    private long defaultOwnerId;

    @Column(name = "EMAIL_HMAC", updatable = false, length = 64)
    private @Nullable String emailHmac;

    @Column(name = "EXTERNAL_ID_HMAC", updatable = false, length = 64)
    private @Nullable String externalIdHmac;

    @Column(name = "RUN_ID", nullable = false, updatable = false)
    private long runId;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.EPOCH;

    /** For JPA. */
    protected ImportOwnerPending() {}

    /**
     * The pending row of a case the load is writing, if its owner calls for one.
     *
     * @param caseId the case just created
     * @param owner the owner the load gave it
     * @param runId the run loading it
     * @param key {@code import.hash-key}
     * @param at when it was loaded
     * @return the row, or empty when the case kept its legacy owner, is closed, or names nobody who could be matched
     */
    static Optional<ImportOwnerPending> of(
            long caseId, PeopleMatcher.Owner owner, long runId, SecretKey key, Instant at) {
        PeopleMatcher.Identity legacy = owner.pending();
        String email = legacy == null ? null : legacy.email();
        String externalId = legacy == null ? null : legacy.externalId();
        if (email == null && externalId == null) {
            return Optional.empty();
        }
        ImportOwnerPending row = new ImportOwnerPending();
        row.caseId = caseId;
        row.defaultOwnerId = owner.userId();
        row.emailHmac = email == null ? null : emailHmac(key, email);
        row.externalIdHmac = externalId == null ? null : externalIdHmac(key, externalId);
        row.runId = runId;
        row.createdAt = at;
        return Optional.of(row);
    }

    /**
     * The keyed hash of an email address, trimmed and lower-cased, since people match by address case-insensitively.
     *
     * @param key {@code import.hash-key}
     * @param email the address
     * @return the hex HMAC
     */
    static String emailHmac(SecretKey key, String email) {
        return KeyedHash.hex(key, "email:" + email.strip().toLowerCase(Locale.ROOT));
    }

    /**
     * The keyed hash of an external id, exactly as matching people compares it.
     *
     * @param key {@code import.hash-key}
     * @param externalId the identity provider's subject
     * @return the hex HMAC
     */
    static String externalIdHmac(SecretKey key, String externalId) {
        return KeyedHash.hex(key, "external-id:" + externalId);
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof ImportOwnerPending that && id != null && id.equals(that.id));
    }

    @Override
    public int hashCode() {
        return ImportOwnerPending.class.hashCode();
    }
}
