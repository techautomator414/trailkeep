// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

/**
 * A person's preferences (WP-27, BUILD_PLAN §3.1, §10.1). Every method takes the person's own id and touches only
 * their row. A person who never saved the page has the defaults.
 */
public interface UserPreferenceService {

    /**
     * Whether the single-key shortcuts ({@code /} and {@code N}) are on for the person; they are on by default and
     * can be turned off (WCAG 2.1.4).
     *
     * @param userId the person
     * @return {@code true} unless the person turned them off
     */
    boolean shortcutsEnabled(long userId);

    /**
     * Saves the shortcuts switch and audits {@code PREFERENCES_CHANGED} with the old and new value; saving the value
     * the person already has writes nothing.
     *
     * @param userId the person
     * @param displayName their name, as the audit event keeps it
     * @param enabled the new value
     */
    void setShortcutsEnabled(long userId, String displayName, boolean enabled);
}
