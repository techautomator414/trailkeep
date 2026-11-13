// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.time.chrono.IsoChronology;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.ResolverStyle;
import java.util.Locale;

/** A mapping's declared date or time {@code format} (design §6.3), as {@link FieldTransformer} parses it. */
final class DeclaredFormat {

    /**
     * ISO-8601 date and time with a space for the {@code T}, as database clients write them, read when a mapping
     * declares no format (WP-26 part ae1, docs/importer/export-recipes.md): {@code psql} writes {@code 2019-03-01
     * 09:30:00.25}, and a {@code timestamptz} with an offset of hours, {@code +00}, or of hours and minutes, {@code
     * +05:30}. Strict, as the ISO formats are.
     */
    static final DateTimeFormatter SQL = new DateTimeFormatterBuilder()
            .append(DateTimeFormatter.ISO_LOCAL_DATE)
            .appendLiteral(' ')
            .append(DateTimeFormatter.ISO_LOCAL_TIME)
            .optionalStart()
            .appendOffset("+HH:mm", "Z")
            .optionalEnd()
            .toFormatter(Locale.ROOT)
            .withChronology(IsoChronology.INSTANCE)
            .withResolverStyle(ResolverStyle.STRICT);

    private DeclaredFormat() {}

    /**
     * A declared {@code format}, resolved strictly, as the ISO formats already are: {@code 2026-02-30} is refused
     * ({@code BAD_DATE}/{@code BAD_TIMESTAMP}), never moved to the 28th as the default smart resolver would. Strict
     * resolution reads {@code yyyy} as a year of an era and then requires the era, which no legacy export writes, so
     * an unquoted {@code y} is read as {@code u}, the proleptic year: {@code yyyy} and {@code uuuu} mean the same
     * here, and eras are not supported. Text in quotes is left alone.
     *
     * @param format the mapping's pattern
     * @return the strict formatter
     */
    static DateTimeFormatter of(String format) {
        StringBuilder pattern = new StringBuilder(format.length());
        boolean quoted = false;
        for (int i = 0; i < format.length(); i++) {
            char c = format.charAt(i);
            if (c == '\'') {
                quoted = !quoted;
            }
            pattern.append(!quoted && c == 'y' ? 'u' : c);
        }
        return DateTimeFormatter.ofPattern(pattern.toString(), Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);
    }
}
