// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One row's mapped fields as the extract typed them (WP-26 part k1a), by the mapping's field name: text, keys and
 * lookup results as {@link String}, times as {@link Instant}, days as {@link LocalDate}, flags as {@link Boolean} and
 * sizes as {@link Long}. A field that failed or was not mapped is {@code null}.
 */
public final class StagedValues {

    private final Map<String, @Nullable Object> values;

    /**
     * The typed values.
     *
     * @param values by field name
     */
    public StagedValues(Map<String, @Nullable Object> values) {
        this.values = new HashMap<>(values);
    }

    @Nullable
    String text(String field) {
        return (String) values.get(field);
    }

    @Nullable
    Instant instant(String field) {
        return (Instant) values.get(field);
    }

    @Nullable
    LocalDate date(String field) {
        return (LocalDate) values.get(field);
    }

    @Nullable
    Boolean flag(String field) {
        return (Boolean) values.get(field);
    }

    @Nullable
    Long number(String field) {
        return (Long) values.get(field);
    }
}
