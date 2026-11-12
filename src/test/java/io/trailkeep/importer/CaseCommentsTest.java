// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedValues;
import io.trailkeep.importer.staging.StagingKey;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * A case's description and comment texts at load (WP-26 part u, design §6.2, §6.4, §7.2): the description cut to what
 * a case takes, and a comment longer than one comment takes split into numbered parts that keep every character and
 * never split a surrogate pair or a Markdown escape.
 */
class CaseCommentsTest {

    @Test
    void commentThatFitsStaysWholeAndLongerOneIsSplitIntoNumberedParts() {
        assertThat(CaseComments.parts("b".repeat(20_000))).containsExactly("b".repeat(20_000));

        List<String> parts = CaseComments.parts("b".repeat(45_000));

        assertThat(parts)
                .hasSize(3)
                .allSatisfy(part -> assertThat(part.length()).isLessThanOrEqualTo(20_000));
        assertThat(parts.get(0)).startsWith("(1/3)\nbbb");
        assertThat(parts.get(2)).startsWith("(3/3)\nbbb");
        assertThat(String.join("", parts.stream().map(CaseCommentsTest::body).toList()))
                .isEqualTo("b".repeat(45_000));
    }

    @Test
    void splitNeverCutsSurrogatePairOrEscape() {
        String emoji = new String(Character.toChars(0x1F600));
        String withPair = "b".repeat(19_975) + emoji + "b".repeat(100);
        String withEscape = "b".repeat(19_975) + "\\*" + "b".repeat(100);

        for (String text : List.of(withPair, withEscape)) {
            List<String> parts = CaseComments.parts(text);
            assertThat(body(parts.get(0))).hasSize(19_975);
            assertThat(String.join(
                            "", parts.stream().map(CaseCommentsTest::body).toList()))
                    .isEqualTo(text);
        }
    }

    @Test
    void descriptionIsCutToWhatCasesTakeAsCasesHoldIt() {
        CaseComments plain = new CaseComments(YAMLMapper.builder().build().readTree("version: 1"));
        CaseComments html = new CaseComments(YAMLMapper.builder()
                .build()
                .readTree("entities: { case: { fields: { description: { text: d, format: html } } } }"));

        assertThat(plain.description("d".repeat(20_005))).isEqualTo("d".repeat(20_000));
        assertThat(plain.tooLong("d".repeat(20_001))).isTrue();
        assertThat(html.description("<b>Start</b> &amp; more")).isEqualTo("Start & more");
    }

    @Test
    void commentIsFormattedAsItsMappingDeclares() {
        assertThat(CaseComments.body(comment("1. *first*", "plain"))).isEqualTo("1\\. \\*first\\*");
        assertThat(CaseComments.body(comment("**bold**", "markdown"))).isEqualTo("**bold**");
        assertThat(CaseComments.body(comment("<i>a</i> *b*", "html"))).isEqualTo("a \\*b\\*");
    }

    private static StagedComment comment(String body, String format) {
        return new StagedComment(
                new StagingKey(1L, "n1"), "h", "c1", new StagedValues(Map.of("body", body, "bodyFormat", format)));
    }

    /** A part without its number. */
    private static String body(String part) {
        return part.substring(part.indexOf('\n') + 1);
    }
}
