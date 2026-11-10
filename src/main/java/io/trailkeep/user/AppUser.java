// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * An application user ({@code APP_USER}), provisioned from the identity provider on first login. The external id is
 * the provider's stable subject; no password is stored. Users are disabled, never deleted.
 */
@Entity
@Table(name = "APP_USER")
class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "appUserSeq")
    @SequenceGenerator(name = "appUserSeq", sequenceName = "SEQ_APP_USER", allocationSize = 50)
    @Column(name = "ID", nullable = false)
    private @Nullable Long id;

    @Column(name = "EXTERNAL_ID", nullable = false, length = 255, updatable = false)
    private String externalId;

    @Column(name = "DISPLAY_NAME", nullable = false, length = 200)
    private String displayName;

    @Column(name = "EMAIL", nullable = false, length = 320)
    private String email;

    @Column(name = "DISABLED", nullable = false)
    private boolean disabled;

    @Column(name = "LAST_LOGIN_AT")
    private @Nullable Instant lastLoginAt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt;

    /** For JPA. */
    protected AppUser() {
        this.externalId = "";
        this.displayName = "";
        this.email = "";
        this.createdAt = Instant.EPOCH;
    }

    AppUser(String externalId, String displayName, String email, Instant createdAt) {
        this.externalId = externalId;
        this.displayName = displayName;
        this.email = email;
        this.createdAt = createdAt;
    }

    @Nullable
    Long getId() {
        return id;
    }

    String getExternalId() {
        return externalId;
    }

    String getDisplayName() {
        return displayName;
    }

    String getEmail() {
        return email;
    }

    /**
     * Whether this is one of the seeded principals that act for the importer (ADR-0015), not a person.
     *
     * @return true for a reserved external id
     */
    boolean isPrincipal() {
        return externalId.startsWith(ImportPeopleService.PRINCIPAL_PREFIX);
    }

    boolean isDisabled() {
        return disabled;
    }

    @Nullable
    Instant getLastLoginAt() {
        return lastLoginAt;
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    /** Refreshes the profile attributes the identity provider owns. */
    void updateProfile(String newDisplayName, String newEmail) {
        this.displayName = newDisplayName;
        this.email = newEmail;
    }

    void recordLogin(Instant at) {
        this.lastLoginAt = at;
    }

    void disable() {
        this.disabled = true;
    }

    void enable() {
        this.disabled = false;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof AppUser user && id != null && id.equals(user.id));
    }

    @Override
    public int hashCode() {
        return AppUser.class.hashCode();
    }

    /** Ids and flags only: never the email address (Part B "Logging"). */
    @Override
    public String toString() {
        return "AppUser[id=" + id + ", disabled=" + disabled + "]";
    }
}
