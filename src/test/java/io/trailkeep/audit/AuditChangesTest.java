// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.audit.AuditChanges.Field;
import io.trailkeep.audit.AuditChanges.Value;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@link AuditChanges#fields()} reads what {@link SnapshotDiff} stores (#274): plain values as text, a redacted one as
 * its length only, so neither its plaintext nor its HMAC can reach a reader.
 */
class AuditChangesTest {

    private static final String SUBJECT = "Synthetic subject naming a person";

    record Snapshot(String title, @Redacted @Nullable String subject, int days) {}

    private final SnapshotDiff diff = new SnapshotDiff(AuditHmac.random());

    @Test
    void editsReadAsChangedFieldsWithRedactedOnesAsLengthsOnly() {
        AuditChanges changes = diff.diff(new Snapshot("Old", "short", 3), new Snapshot("New <b>", SUBJECT, 3));
        String hmac = diff.redact(SUBJECT).hmac();

        assertThat(changes.fields())
                .containsExactly(
                        new Field("title", new Value("Old", null), new Value("New <b>", null)),
                        new Field("subject", new Value(null, 5), new Value(null, SUBJECT.length())));
        assertThat(changes.fields().toString()).doesNotContain(SUBJECT, hmac, "short");
    }

    @Test
    void creationsHaveNoBeforeValuesAndNumbersReadAsText() {
        assertThat(diff.diff(null, new Snapshot("T", null, 4)).fields())
                .containsExactly(
                        new Field("title", null, new Value("T", null)),
                        new Field("subject", null, null),
                        new Field("days", null, new Value("4", null)));
    }

    @Test
    void nothingRecordedOrUnreadableJsonHasNoFields() {
        assertThat(AuditChanges.none().fields()).isEmpty();
        assertThat(new AuditChanges("{not json", null).fields()).isEmpty();
    }
}
