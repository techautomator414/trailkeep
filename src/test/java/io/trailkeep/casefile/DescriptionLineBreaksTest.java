// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.Sensitivity;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * A browser counts a textarea's line break as one character for {@code maxlength} but submits it as CRLF, so the
 * 20 000 limit must be checked on the text with single line feeds, as the comment service already does.
 */
class DescriptionLineBreaksTest {

    private static final int LIMIT = 20_000;

    private final Validator validator =
            Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void fullDescriptionWithCrlfLineBreaksIsWithinLimit() {
        String submitted = "x".repeat(LIMIT - 10) + "\r\n".repeat(10);
        assertThat(submitted).hasSize(LIMIT + 10);

        assertThat(validator.validate(fields(submitted))).isEmpty();
        assertThat(validator.validate(changes(submitted))).isEmpty();
        assertThat(fields(submitted).description()).isEqualTo("x".repeat(LIMIT - 10) + "\n".repeat(10));
        assertThat(changes(submitted).description()).isEqualTo("x".repeat(LIMIT - 10) + "\n".repeat(10));
    }

    @Test
    void oneCharacterOverTheLimitIsStillRejected() {
        String tooLong = "x".repeat(LIMIT - 1) + "\r\n" + "y";

        assertThat(validator.validate(fields(tooLong))).hasSize(1);
        assertThat(validator.validate(changes(tooLong))).hasSize(1);
    }

    private static CaseFields fields(String description) {
        return new CaseFields(
                1,
                "Synthetic title",
                CasePriority.NORMAL,
                LocalDate.of(2026, 11, 1),
                1,
                1,
                null,
                description,
                Sensitivity.STANDARD);
    }

    private static CaseChanges changes(String description) {
        return new CaseChanges("Synthetic title", CasePriority.NORMAL, null, description);
    }
}
