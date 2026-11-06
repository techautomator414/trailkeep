// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Objects;
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

    @Test
    void truncationNeverSplitsSurrogatePair() {
        String emoji = new String(Character.toChars(0x1F600));
        String straddling = "n".repeat(199) + emoji + "tail";
        String fitting = "n".repeat(198) + emoji + "tail";

        assertThat(snapshot(straddling)).isEqualTo("n".repeat(199));
        assertThat(snapshot(fitting)).isEqualTo("n".repeat(198) + emoji);
    }

    private static String snapshot(String name) {
        return Objects.requireNonNull(
                new AuditEntry(1L, name, AuditOrigin.UI, null, "TEAM", 2L, "CREATE", AuditChanges.none()).actorName());
    }

    private static AuditEntry entry(String entityType, String action) {
        return new AuditEntry(null, null, AuditOrigin.SYSTEM, null, entityType, null, action, AuditChanges.none());
    }
}
