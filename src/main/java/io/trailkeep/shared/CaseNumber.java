// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared;

import java.io.Serializable;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A case number of the shape {@code PREFIX-YYYY-NNNNNN}: a configurable prefix of one to eight upper-case letters or
 * digits, the four-digit opening year, and a sequence number padded to six digits that widens (up to ten digits,
 * the width of the {@code CASE_NUMBER} column) and never resets. The textual form is canonical, so
 * {@code parse(n.toString()).equals(n)} always holds and {@link #parse} rejects non-canonical padding.
 *
 * @param prefix the deployment-configured prefix
 * @param year the year the case was opened
 * @param sequence the value drawn from {@code SEQ_CASE_NUMBER}
 */
public record CaseNumber(String prefix, int year, long sequence) implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Pattern PREFIX = Pattern.compile("[A-Z0-9]{1,8}");
    private static final Pattern SHAPE = Pattern.compile("([A-Z0-9]{1,8})-(\\d{4})-(\\d{6}|[1-9]\\d{6,9})");
    private static final long MAX_SEQUENCE = 9_999_999_999L;

    /**
     * Validates the components.
     *
     * @throws IllegalArgumentException if a component is outside the shape described above
     */
    public CaseNumber {
        Objects.requireNonNull(prefix, "prefix");
        if (!PREFIX.matcher(prefix).matches()) {
            throw new IllegalArgumentException("Case number prefix must be 1-8 upper-case letters or digits");
        }
        if (year < 1000 || year > 9999) {
            throw new IllegalArgumentException("Case number year must have four digits");
        }
        if (sequence < 1 || sequence > MAX_SEQUENCE) {
            throw new IllegalArgumentException("Case number sequence must be between 1 and " + MAX_SEQUENCE);
        }
    }

    /**
     * Parses the canonical textual form.
     *
     * @param text a case number such as {@code CASE-2026-000123}
     * @return the parsed case number
     * @throws IllegalArgumentException if {@code text} is not a canonical case number
     */
    public static CaseNumber parse(String text) {
        Matcher matcher = SHAPE.matcher(Objects.requireNonNull(text, "text"));
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Not a case number of the shape PREFIX-YYYY-NNNNNN");
        }
        return new CaseNumber(matcher.group(1), Integer.parseInt(matcher.group(2)), Long.parseLong(matcher.group(3)));
    }

    /** {@return the canonical textual form, for example {@code CASE-2026-000123}} */
    @Override
    public String toString() {
        return String.format(Locale.ROOT, "%s-%04d-%06d", prefix, year, sequence);
    }
}
