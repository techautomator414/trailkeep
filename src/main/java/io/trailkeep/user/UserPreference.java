// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A person's preferences ({@code USER_PREFERENCE}, BUILD_PLAN §3.1, §10.1): one row per person, keyed by their id and
 * created the first time they save the preferences page. Not case-rooted: it holds no case data.
 */
@Entity
@Table(name = "USER_PREFERENCE")
class UserPreference {

    @Id
    @Column(name = "USER_ID", nullable = false, updatable = false)
    private long userId;

    @Column(name = "SHORTCUTS_ENABLED", nullable = false)
    private boolean shortcutsEnabled = true;

    @Column(name = "UPDATED_AT", nullable = false)
    private Instant updatedAt = Instant.EPOCH;

    /** For JPA. */
    protected UserPreference() {}

    /**
     * A new row.
     *
     * @param userId the person
     * @param shortcutsEnabled whether single-key shortcuts are on
     * @param now when it was saved
     */
    UserPreference(long userId, boolean shortcutsEnabled, Instant now) {
        this.userId = userId;
        this.shortcutsEnabled = shortcutsEnabled;
        this.updatedAt = now;
    }

    boolean isShortcutsEnabled() {
        return shortcutsEnabled;
    }

    void change(boolean newShortcutsEnabled, Instant now) {
        this.shortcutsEnabled = newShortcutsEnabled;
        this.updatedAt = now;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof UserPreference preference && userId == preference.userId);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(userId);
    }

    @Override
    public String toString() {
        return "UserPreference[" + userId + "]";
    }
}
