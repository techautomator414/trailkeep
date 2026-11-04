// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Something in a title that looks like PHI (BUILD_PLAN §18, "PHI in titles"). Titles go into emails, so the create
 * form warns before saving one of these. A heuristic: false positives are expected and the person may confirm.
 */
public enum TitleWarning {
    /** Nine digits as {@code 123-45-6789}, {@code 123 45 6789} or {@code 123456789}. */
    SSN("\\b\\d{3}[- ]?\\d{2}[- ]?\\d{4}\\b"),
    /** "MRN", "medical record" or a run of seven or more digits. */
    MEDICAL_RECORD_NUMBER("(?i)\\bMRN\\b|\\bmedical record\\b|\\b\\d{7,}\\b"),
    /** "DOB", "date of birth", "born", or a date written with slashes. */
    DATE_OF_BIRTH("(?i)\\bDOB\\b|\\bdate of birth\\b|\\bborn\\b|\\b\\d{1,2}/\\d{1,2}/\\d{2,4}\\b");

    private final Pattern pattern;

    TitleWarning(String regex) {
        this.pattern = Pattern.compile(regex);
    }

    static Set<TitleWarning> in(String title) {
        Set<TitleWarning> found = EnumSet.noneOf(TitleWarning.class);
        for (TitleWarning warning : values()) {
            if (warning.pattern.matcher(title).find()) {
                found.add(warning);
            }
        }
        return Collections.unmodifiableSet(found);
    }
}
