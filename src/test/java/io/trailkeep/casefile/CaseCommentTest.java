// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class CaseCommentTest {

    private static final Instant NOW = Instant.parse("2026-11-04T12:00:00Z");

    @Test
    void bodiesUpToTwentyThousandCharactersAreComments() {
        CaseComment comment = CaseComment.of(7, "x".repeat(20_000), 3L, "Synthetic Author", NOW);

        assertThat(comment.getBody()).hasSize(CaseComment.MAX_BODY);
        assertThat(comment.getAuthorName()).isEqualTo("Synthetic Author");
        assertThat(comment.getCreatedAt()).isEqualTo(NOW);
    }

    @Test
    void anImportedCommentHasNoAuthorIdButKeepsTheName() {
        CaseComment comment = CaseComment.of(7, "Imported", null, "Former Colleague", NOW);

        assertThat(comment.getAuthorId()).isNull();
        assertThat(comment.getAuthorName()).isEqualTo("Former Colleague");
    }

    @Test
    void blankOrOversizedBodiesAreRefused() {
        assertThatThrownBy(() -> CaseComment.of(7, " \n ", 3L, "Synthetic Author", NOW))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CaseComment.of(7, "x".repeat(20_001), 3L, "Synthetic Author", NOW))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTextNeverAppearsInToString() {
        CaseComment comment = CaseComment.of(7, "Synthetic PHI-shaped text", 3L, "Synthetic Author", NOW);

        assertThat(comment.toString()).doesNotContain("PHI").contains("caseId=7");
    }

    @Test
    void equalityIsByIdOnly() {
        CaseComment comment = CaseComment.of(7, "a", 3L, "Synthetic Author", NOW);

        Object same = comment;
        assertThat(comment.equals(same)).isTrue();
        assertThat(comment).isNotEqualTo(CaseComment.of(7, "a", 3L, "Synthetic Author", NOW));
        assertThat(comment.hashCode())
                .isEqualTo(CaseComment.of(8, "b", 4L, "Other", NOW).hashCode());
    }
}
