// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import java.time.InstantSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads and saves a person's preferences; a change is audited in the same transaction. */
@Service
@Transactional
class DefaultUserPreferenceService implements UserPreferenceService {

    private static final String ENTITY = "USER_PREFERENCE";

    private final UserPreferenceRepository preferences;
    private final AuditRecorder audit;
    private final InstantSource clock;

    DefaultUserPreferenceService(UserPreferenceRepository preferences, AuditRecorder audit, InstantSource clock) {
        this.preferences = preferences;
        this.audit = audit;
        this.clock = clock;
    }

    /** What the audit trail keeps of the preferences: the values, none of which is personal text. */
    record Snapshot(boolean shortcutsEnabled) {}

    @Override
    @Transactional(readOnly = true)
    public boolean shortcutsEnabled(long userId) {
        return preferences
                .findById(userId)
                .map(UserPreference::isShortcutsEnabled)
                .orElse(true);
    }

    @Override
    public void setShortcutsEnabled(long userId, String displayName, boolean enabled) {
        UserPreference current = preferences.findById(userId).orElse(null);
        boolean before = current == null || current.isShortcutsEnabled();
        if (before == enabled) {
            return;
        }
        if (current == null) {
            preferences.save(new UserPreference(userId, enabled, clock.instant()));
        } else {
            current.change(enabled, clock.instant());
        }
        audit.record(new AuditEntry(
                userId,
                displayName,
                AuditOrigin.UI,
                null,
                ENTITY,
                userId,
                "PREFERENCES_CHANGED",
                audit.diff(new Snapshot(before), new Snapshot(enabled))));
    }
}
