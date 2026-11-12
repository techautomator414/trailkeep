// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * A legacy row's canonical key (design §5 "Keys"): the same key whether it was read from a database or a file, so
 * staging, the ledger and delta runs agree. Integral numbers are plain decimals ({@code 1}, {@code 1.0} and
 * {@code "1"} agree; {@code 100} stays {@code 100}); GUIDs are lower-case; strings are as read, or lower-cased for a
 * {@code keyCase: insensitive} entity. Components are joined with {@code /}, with {@code /} and {@code \} escaped.
 */
final class CanonicalKey {

    /** A GUID as text, as a file holds what a database returns as a UUID. */
    private static final Pattern GUID =
            Pattern.compile("[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}");

    private CanonicalKey() {}

    /**
     * The canonical key of one row.
     *
     * @param components the key columns' values, in the mapping's order
     * @param insensitive whether the entity declares {@code keyCase: insensitive}
     * @return the key, or {@code null} when a component is missing or a number is not integral (an issue)
     */
    static @Nullable String of(List<@Nullable Object> components, boolean insensitive) {
        StringBuilder key = new StringBuilder();
        for (Object component : components) {
            String part = part(component, insensitive);
            if (part == null) {
                return null;
            }
            if (!key.isEmpty()) {
                key.append('/');
            }
            key.append(part.replace("\\", "\\\\").replace("/", "\\/"));
        }
        return key.toString();
    }

    private static @Nullable String part(@Nullable Object component, boolean insensitive) {
        return switch (component) {
            case null -> null;
            case UUID uuid -> uuid.toString();
            case Number number -> integral(new BigDecimal(number.toString()));
            case String text when text.isEmpty() -> null;
            case String text -> insensitive || GUID.matcher(text).matches() ? text.toLowerCase(Locale.ROOT) : text;
            default -> component.toString();
        };
    }

    private static @Nullable String integral(BigDecimal number) {
        BigDecimal stripped = number.stripTrailingZeros();
        return stripped.scale() > 0 ? null : stripped.toPlainString();
    }
}
