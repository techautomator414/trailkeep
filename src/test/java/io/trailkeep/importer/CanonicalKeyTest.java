// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Canonical keys (design §5 "Keys"): the same key from a database and from its exported file. */
class CanonicalKeyTest {

    @Test
    void integralNumbersArePlainDecimalsAndOneHundredStaysOneHundred() {
        assertThat(CanonicalKey.of(List.of(1), false)).isEqualTo("1");
        assertThat(CanonicalKey.of(List.of(new BigDecimal("1.0")), false)).isEqualTo("1");
        assertThat(CanonicalKey.of(List.of("1"), false)).isEqualTo("1");
        assertThat(CanonicalKey.of(List.of(new BigDecimal("100")), false)).isEqualTo("100");
        assertThat(CanonicalKey.of(List.of(new BigDecimal("1E+2")), false)).isEqualTo("100");
        assertThat(CanonicalKey.of(List.of(new BigDecimal("1.5")), false))
                .as("not integral")
                .isNull();
    }

    @Test
    void guidsAreLowerCaseWhetherReadAsUuidsOrAsText() {
        UUID id = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

        assertThat(CanonicalKey.of(List.of(id), false)).isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
        assertThat(CanonicalKey.of(List.of("0F8FAD5B-D9CB-469F-A165-70867728950E"), false))
                .isEqualTo("0f8fad5b-d9cb-469f-a165-70867728950e");
    }

    @Test
    void stringsAreAsReadUnlessTheEntityIsCaseInsensitive() {
        assertThat(CanonicalKey.of(List.of("Ab-1"), false)).isEqualTo("Ab-1");
        assertThat(CanonicalKey.of(List.of("Ab-1"), true)).isEqualTo("ab-1");
    }

    @Test
    void componentsAreJoinedWithEscapedSlashes() {
        assertThat(CanonicalKey.of(List.of("north/east", 7), false)).isEqualTo("north\\/east/7");
        assertThat(CanonicalKey.of(List.of("a\\b", "c"), false)).isEqualTo("a\\\\b/c");
        assertThat(CanonicalKey.of(List.of("north/east", 7), false))
                .as("two components never collide with one holding a slash")
                .isNotEqualTo(CanonicalKey.of(List.of("north", "east/7"), false));
    }

    @Test
    void missingComponentsGiveNoKey() {
        assertThat(CanonicalKey.of(Arrays.asList("x", null), false)).isNull();
        assertThat(CanonicalKey.of(List.of(""), false)).isNull();
    }
}
