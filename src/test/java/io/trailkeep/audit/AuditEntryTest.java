// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AuditEntryTest {

    @Test
    void rejectsBlankOrOverlongCodes() {
        assertThatThrownBy(() -> entry(" ", "CREATE")).hasMessageContaining("entityType");
        assertThatThrownBy(() -> entry("TEAM", "A".repeat(41))).hasMessageContaining("action");
    }

    @Test
    void truncatesAnOverlongActorNameSnapshot() {
        AuditEntry entry =
                new AuditEntry(1L, "n".repeat(250), AuditOrigin.UI, null, "TEAM", 2L, "CREATE", AuditChanges.none());

        assertThat(entry.actorName()).hasSize(200);
    }

    private static AuditEntry entry(String entityType, String action) {
        return new AuditEntry(null, null, AuditOrigin.SYSTEM, null, entityType, null, action, AuditChanges.none());
    }
}
