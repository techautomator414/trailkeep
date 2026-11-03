// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class CaseNumberTest {

    @Test
    void formatsWithSixDigitPadding() {
        assertThat(new CaseNumber("CASE", 2026, 123)).hasToString("CASE-2026-000123");
    }

    @Test
    void widensPastSixDigits() {
        assertThat(new CaseNumber("CASE", 2026, 1_234_567)).hasToString("CASE-2026-1234567");
    }

    @ParameterizedTest
    @ValueSource(strings = {"CASE-2026-000123", "A-1999-000001", "ABCDEFG8-2026-999999", "X1-2026-9999999999"})
    void parsesItsOwnCanonicalForm(String text) {
        assertThat(CaseNumber.parse(text)).hasToString(text);
    }

    @Test
    void parseExposesComponents() {
        CaseNumber number = CaseNumber.parse("CASE-2026-000123");

        assertThat(number.prefix()).isEqualTo("CASE");
        assertThat(number.year()).isEqualTo(2026);
        assertThat(number.sequence()).isEqualTo(123L);
        assertThat(number).isEqualTo(new CaseNumber("CASE", 2026, 123));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "CASE-2026-00123",
                "case-2026-000123",
                "ABCDEFGHI-2026-000123",
                "CASE-26-000123",
                "CASE-2026-0001234",
                "CASE-2026-12345678901",
                "CASE-2026-000000",
                "CASE_2026_000123",
                " CASE-2026-000123",
                "CA-SE-2026-000123",
                "CASE-2026-000123\n"
            })
    void rejectsAnythingElse(String text) {
        assertThatIllegalArgumentException().isThrownBy(() -> CaseNumber.parse(text));
    }

    @Test
    void constructorValidatesComponents() {
        assertThatIllegalArgumentException().isThrownBy(() -> new CaseNumber("", 2026, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new CaseNumber("CASE-X", 2026, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new CaseNumber("CASE", 999, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new CaseNumber("CASE", 10_000, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> new CaseNumber("CASE", 2026, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new CaseNumber("CASE", 2026, 10_000_000_000L));
    }

    @Test
    void notFoundCarriesTheNumberAndNothingElse() {
        CaseNumber number = CaseNumber.parse("CASE-2026-000123");

        CaseNotFoundException exception = new CaseNotFoundException(number);

        assertThat(exception.caseNumber()).isEqualTo(number);
        assertThat(exception).hasMessage("Case CASE-2026-000123 not found");
    }
}
