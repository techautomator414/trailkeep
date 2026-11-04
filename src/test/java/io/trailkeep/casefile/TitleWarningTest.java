// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TitleWarningTest {

    @Test
    void plainTitlesRaiseNothing() {
        assertThat(TitleWarning.in("Vendor access review for Q3 2026")).isEmpty();
        assertThat(TitleWarning.in("Policy exception 12-34")).isEmpty();
    }

    @Test
    void socialSecurityNumberShapesAreFlagged() {
        assertThat(TitleWarning.in("Ref 123-45-6789")).containsExactly(TitleWarning.SSN);
        assertThat(TitleWarning.in("Ref 123 45 6789")).containsExactly(TitleWarning.SSN);
    }

    @Test
    void medicalRecordNumbersAreFlagged() {
        assertThat(TitleWarning.in("mrn missing from chart")).containsExactly(TitleWarning.MEDICAL_RECORD_NUMBER);
        assertThat(TitleWarning.in("Patient 4455667 transfer")).containsExactly(TitleWarning.MEDICAL_RECORD_NUMBER);
    }

    @Test
    void datesOfBirthAreFlaggedInEnumOrderWithOtherPatterns() {
        assertThat(TitleWarning.in("DOB 1/2/1980 and 123456789"))
                .containsExactly(TitleWarning.SSN, TitleWarning.MEDICAL_RECORD_NUMBER, TitleWarning.DATE_OF_BIRTH);
        assertThat(TitleWarning.in("Born in the wrong system")).containsExactly(TitleWarning.DATE_OF_BIRTH);
    }
}
