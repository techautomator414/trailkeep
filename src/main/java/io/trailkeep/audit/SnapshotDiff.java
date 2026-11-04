// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/** The service-layer snapshot diff with the redaction policy; see {@link AuditRecorder#diff}. */
final class SnapshotDiff {

    private final AuditHmac hmac;
    private final JsonMapper json = JsonMapper.builder().build();

    SnapshotDiff(AuditHmac hmac) {
        this.hmac = hmac;
    }

    <T extends Record> AuditChanges diff(@Nullable T before, @Nullable T after) {
        Class<? extends Record> type = commonType(before, after);
        Map<String, @Nullable Object> oldValues = new LinkedHashMap<>();
        Map<String, @Nullable Object> newValues = new LinkedHashMap<>();
        for (RecordComponent component : type.getRecordComponents()) {
            Object oldValue = before == null ? null : read(component, before);
            Object newValue = after == null ? null : read(component, after);
            if (before != null && after != null && Objects.equals(oldValue, newValue)) {
                continue;
            }
            boolean redacted = component.isAnnotationPresent(Redacted.class);
            oldValues.put(component.getName(), render(oldValue, redacted));
            newValues.put(component.getName(), render(newValue, redacted));
        }
        if (oldValues.isEmpty()) {
            return AuditChanges.none();
        }
        return new AuditChanges(before == null ? null : write(oldValues), after == null ? null : write(newValues));
    }

    private static Class<? extends Record> commonType(@Nullable Record before, @Nullable Record after) {
        if (before == null && after == null) {
            throw new IllegalArgumentException("before and after are both null");
        }
        if (before != null && after != null && before.getClass() != after.getClass()) {
            throw new IllegalArgumentException("snapshots of different types: "
                    + before.getClass().getName() + " and " + after.getClass().getName());
        }
        return before != null
                ? before.getClass()
                : Objects.requireNonNull(after).getClass();
    }

    private String write(Map<String, @Nullable Object> values) {
        return json.writeValueAsString(values);
    }

    private @Nullable Object render(@Nullable Object value, boolean redacted) {
        if (value == null) {
            return null;
        }
        if (redacted) {
            String text = String.valueOf(value);
            Map<String, Object> digest = new LinkedHashMap<>();
            digest.put("hmac", hmac.hex(text));
            digest.put("length", text.codePointCount(0, text.length()));
            return digest;
        }
        if (value instanceof Number || value instanceof Boolean || value instanceof String) {
            return value;
        }
        return value instanceof Enum<?> constant ? constant.name() : value.toString();
    }

    private static @Nullable Object read(RecordComponent component, Record snapshot) {
        Method accessor = component.getAccessor();
        try {
            // Snapshot records are usually package-private in their own module; the accessor is still public.
            accessor.setAccessible(true);
            return accessor.invoke(snapshot);
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new IllegalStateException("Cannot read " + component.getName() + " of a snapshot", e);
        }
    }
}
