// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * The old and new values of an audited change as JSON objects, already redacted. Produced by
 * {@link AuditRecorder#diff}; callers do not build the JSON themselves.
 *
 * @param oldValuesJson the changed fields before the change, or {@code null} on creation or when nothing is recorded
 * @param newValuesJson the changed fields after the change, or {@code null} on deletion or when nothing is recorded
 */
public record AuditChanges(
        @Nullable String oldValuesJson, @Nullable String newValuesJson) {

    private static final AuditChanges NONE = new AuditChanges(null, null);
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, @Nullable Object>> VALUES = new TypeReference<>() {};

    /**
     * No old or new values, for events such as a login or a restricted-case view.
     *
     * @return the empty change set
     */
    public static AuditChanges none() {
        return NONE;
    }

    /**
     * One stored value as a reader may show it: its text, or for a {@link Redacted} one only its length. The HMAC is
     * never carried, so nothing that reads this can print it.
     *
     * @param text the value as text, or {@code null} if it is redacted
     * @param redactedLength the redacted value's length in code points, or {@code null} if it is not redacted
     */
    public record Value(@Nullable String text, @Nullable Integer redactedLength) {

        private static @Nullable Value of(@Nullable Object stored) {
            if (stored == null) {
                return null;
            }
            // SnapshotDiff writes scalars, and an object only for a redaction: any object is shown as one.
            if (stored instanceof Map<?, ?> digest) {
                return new Value(null, digest.get("length") instanceof Number length ? length.intValue() : 0);
            }
            return new Value(String.valueOf(stored), null);
        }
    }

    /**
     * One changed field.
     *
     * @param name the snapshot component's name
     * @param before its value before, or {@code null} if there was none
     * @param after its value after, or {@code null} if there is none
     */
    public record Field(
            String name, @Nullable Value before, @Nullable Value after) {}

    /**
     * The changed fields in stored order, the old values' first; empty if nothing is recorded or the JSON is
     * unreadable.
     *
     * @return the fields
     */
    public List<Field> fields() {
        Map<String, @Nullable Object> before = parse(oldValuesJson);
        Map<String, @Nullable Object> after = parse(newValuesJson);
        Set<String> names = new LinkedHashSet<>(before.keySet());
        names.addAll(after.keySet());
        return names.stream()
                .map(name -> new Field(name, Value.of(before.get(name)), Value.of(after.get(name))))
                .toList();
    }

    private static Map<String, @Nullable Object> parse(@Nullable String json) {
        if (json == null) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, VALUES);
        } catch (JacksonException unreadable) {
            return Map.of();
        }
    }
}
