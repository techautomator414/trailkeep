// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** How the Oracle Text adapter turns a search's words into an index query and the words {@code LIKE} still checks. */
class OracleTextQueryTest {

    /** Only {@code %run%} terms of lower-case ASCII letters and digits, joined by {@code AND}; or nothing. */
    private static final String SAFE = "(%[a-z0-9]{3,64}%( AND %[a-z0-9]{3,64}%)*)?";

    @Test
    void aWordOfLettersAndDigitsIsMatchedByTheIndexAlone() {
        OracleTextQuery query = of("Kestrel 3fa9c1");

        assertThat(query.contains()).isEqualTo("%kestrel% AND %3fa9c1%");
        assertThat(query.likeWords()).isEmpty();
    }

    @Test
    void anyOtherWordIsNarrowedByItsLongestRunAndCheckedByLike() {
        OracleTextQuery query = of("kestrel-escalation under_score straße");

        assertThat(query.contains()).isEqualTo("%escalation% AND %under% AND %stra%");
        assertThat(query.likeWords()).containsExactly("kestrel-escalation", "under_score", "straße");
    }

    @Test
    void wordsWithoutARunOfThreeAreLeftToLike() {
        OracleTextQuery query = of("50% ab é");

        assertThat(query.contains()).isEmpty();
        assertThat(query.likeWords()).containsExactly("50%", "ab", "é");
    }

    @Test
    void atMostThreeRunsAreLookedUpTheLongestFirstAndTheRestAreLeftToLike() {
        OracleTextQuery query = of("abc abcdefg abcd abcdef abcde");

        assertThat(query.contains()).isEqualTo("%abcdefg% AND %abcdef% AND %abcde%");
        assertThat(query.likeWords()).containsExactly("abc", "abcd");
    }

    @Test
    void aRunLongerThanSixtyFourIsCutAndItsWordCheckedByLike() {
        String word = "a".repeat(70);

        OracleTextQuery query = of(word);

        assertThat(query.contains()).isEqualTo("%" + "a".repeat(64) + "%");
        assertThat(query.likeWords()).containsExactly(word);
    }

    @Test
    void theSameRunIsAskedForOnce() {
        OracleTextQuery query = of("kestrel kestrel-x");

        assertThat(query.contains()).isEqualTo("%kestrel%");
        assertThat(query.likeWords()).containsExactly("kestrel-x");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "%",
                "_",
                "{near}",
                "}",
                "a-b",
                "x&y",
                "p|q",
                "ABOUT(x)",
                "NEAR((a,b),5)",
                "$stem",
                "?fuzzy",
                "!soundex",
                "=equiv",
                "a,b;c",
                "[x]",
                "~not",
                ">5",
                "*2",
                "\\",
                "'quote'",
                "\"phrase\"",
                "WITHIN title",
                "x AND y OR z NOT w ACCUM v MINUS u",
                "SYN(car) NT(x) BT(y)",
                "<query><textquery>x</textquery></query>",
                "%drop% _x_ {a}&{b}|{c}"
            })
    void nothingTypedIsReadAsOracleTextSyntax(String search) {
        OracleTextQuery query = of(search);

        assertThat(query.contains()).matches(SAFE);
        assertThat(LikeSearchGateway.words(search)).containsAll(query.likeWords());
    }

    private static OracleTextQuery of(String search) {
        return OracleTextQuery.of(LikeSearchGateway.words(search));
    }
}
