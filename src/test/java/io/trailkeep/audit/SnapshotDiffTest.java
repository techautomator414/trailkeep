// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

class SnapshotDiffTest {

    private static final String SUBJECT = "Vendor invoice dispute for unit 7";
    private static final String DESCRIPTION = "Patient record shared with the wrong clinic";

    enum Status {
        OPEN,
        CLOSED
    }

    record CaseSnapshot(
            String title,
            @Redacted String subject,
            @Redacted @Nullable String description,
            Status status,
            int days) {}

    record OtherSnapshot(String title) {}

    private final SnapshotDiff diff = new SnapshotDiff(fixedKey((byte) 7));

    @Test
    void creationRecordsEveryComponentAsNewValues() {
        AuditChanges changes = diff.diff(null, snapshot("Title", Status.OPEN));

        assertThat(changes.oldValuesJson()).isNull();
        assertThat(changes.newValuesJson())
                .startsWith("{\"title\":\"Title\",\"subject\":{\"hmac\":\"")
                .contains("\"description\":{\"hmac\":\"", "\"status\":\"OPEN\"", "\"days\":30");
    }

    @Test
    void updateRecordsOnlyTheChangedComponentsOnBothSides() {
        AuditChanges changes = diff.diff(snapshot("Old title", Status.OPEN), snapshot("New title", Status.CLOSED));

        assertThat(changes.oldValuesJson()).isEqualTo("{\"title\":\"Old title\",\"status\":\"OPEN\"}");
        assertThat(changes.newValuesJson()).isEqualTo("{\"title\":\"New title\",\"status\":\"CLOSED\"}");
    }

    @Test
    void deletionRecordsEveryComponentAsOldValues() {
        AuditChanges changes = diff.diff(snapshot("Title", Status.OPEN), null);

        assertThat(changes.newValuesJson()).isNull();
        assertThat(changes.oldValuesJson()).contains("\"title\":\"Title\"");
    }

    @Test
    void identicalSnapshotsProduceNoChanges() {
        assertThat(diff.diff(snapshot("Same", Status.OPEN), snapshot("Same", Status.OPEN)))
                .isEqualTo(AuditChanges.none());
    }

    @Test
    void redactedValuesNeverAppearAsPlaintextInEitherSide() {
        CaseSnapshot before = new CaseSnapshot("T", SUBJECT, DESCRIPTION, Status.OPEN, 30);
        CaseSnapshot after = new CaseSnapshot("T", SUBJECT + " (amended)", null, Status.OPEN, 30);

        AuditChanges changes = diff.diff(before, after);

        String all = changes.oldValuesJson() + "|" + changes.newValuesJson();
        assertThat(all).doesNotContain(SUBJECT, DESCRIPTION, "Vendor", "Patient", "clinic", "amended");
        assertThat(changes.oldValuesJson())
                .matches("\\{\"subject\":\\{\"hmac\":\"[0-9a-f]{64}\",\"length\":33},"
                        + "\"description\":\\{\"hmac\":\"[0-9a-f]{64}\",\"length\":43}}");
        assertThat(changes.newValuesJson()).endsWith(",\"length\":43},\"description\":null}");
    }

    @Test
    void redactionIsKeyedDeterministicAndCountsCharactersNotBytes() {
        record Note(@Redacted String text) {}
        Note note = new Note("café ☕");

        String first = diff.diff(null, note).newValuesJson();
        String again = new SnapshotDiff(fixedKey((byte) 7)).diff(null, note).newValuesJson();
        String otherKey = new SnapshotDiff(fixedKey((byte) 8)).diff(null, note).newValuesJson();

        assertThat(first).isEqualTo(again).isNotEqualTo(otherKey).endsWith("\"length\":6}}");
    }

    @Test
    void nonPrimitiveValuesAreWrittenAsText() {
        record Stamp(Instant at) {}

        assertThat(diff.diff(null, new Stamp(Instant.parse("2026-01-02T03:04:05Z")))
                        .newValuesJson())
                .isEqualTo("{\"at\":\"2026-01-02T03:04:05Z\"}");
    }

    @Test
    void rejectsTwoNullsAndMixedTypes() {
        assertThatThrownBy(() -> diff.diff(null, null)).isInstanceOf(IllegalArgumentException.class);
        Record a = snapshot("A", Status.OPEN);
        Record b = new OtherSnapshot("B");
        assertThatThrownBy(() -> diff.diff(a, b))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different types");
    }

    private static CaseSnapshot snapshot(String title, Status status) {
        return new CaseSnapshot(title, SUBJECT, DESCRIPTION, status, 30);
    }

    static AuditHmac fixedKey(byte fill) {
        byte[] key = new byte[AuditHmac.MIN_KEY_BYTES];
        Arrays.fill(key, fill);
        return AuditHmac.fromBase64(Base64.getEncoder().encodeToString(key));
    }
}
