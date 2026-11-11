// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.LegacyText.Format;
import io.trailkeep.importer.LegacyText.Normalised;
import org.junit.jupiter.api.Test;

/** Legacy text normalisation and the comment-only escaping of design §6.4 (R9). */
class LegacyTextTest {

    @Test
    void normalisationStripsNulsControlsAndLoneSurrogatesAndUnifiesLineBreaks() {
        assertThat(LegacyText.normalise("a\u0000b\u0007c\td\r\ne\rf\ng"))
                .isEqualTo(new Normalised("abc\td\ne\nf\ng", true));
        String lone = "lone \uD800 and \uDC00"; // lone surrogates cannot be written as characters
        assertThat(LegacyText.normalise(lone)).isEqualTo(new Normalised("lone � and �", true));
        assertThat(LegacyText.normalise("a pair 😀 stays")).isEqualTo(new Normalised("a pair 😀 stays", false));
        assertThat(LegacyText.normalise("unchanged text, ünïcödé"))
                .isEqualTo(new Normalised("unchanged text, ünïcödé", false));
    }

    @Test
    void plainTextIsEscapedForCommentsSoItReadsAsTyped() {
        String typed = "1. not a list\n- nor this\n+ nor this\n*bold?* _x_ `code` # [link](u) <b> a|b \\ end";

        String escaped = LegacyText.forComment(typed, Format.PLAIN);

        assertThat(escaped)
                .isEqualTo(
                        "1\\. not a list\n\\- nor this\n\\+ nor this\n\\*bold?\\* \\_x\\_ \\`code\\` \\# \\[link\\](u)"
                                + " \\<b\\> a\\|b \\\\ end");
    }

    @Test
    void descriptionsAreNeverEscapedBecauseTheyAreShownAsText() {
        assertThat(LegacyText.forDescription("1. *as typed*", Format.PLAIN)).isEqualTo("1. *as typed*");
        assertThat(LegacyText.forDescription("**md** stays", Format.MARKDOWN)).isEqualTo("**md** stays");
        assertThat(LegacyText.forComment("**md** stays", Format.MARKDOWN)).isEqualTo("**md** stays");
    }

    @Test
    void htmlIsReducedToItsTextAndOnlyTheCommentIsEscaped() {
        String html = "<p>Fish &amp; <b>chips</b></p><script>alert(1)</script><img src=x onerror=y>*";

        assertThat(LegacyText.forDescription(html, Format.HTML)).isEqualTo("Fish & chips*");
        assertThat(LegacyText.forComment(html, Format.HTML)).isEqualTo("Fish & chips\\*");
    }
}
