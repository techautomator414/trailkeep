// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseImportService.ImportRun;
import io.trailkeep.casefile.CaseImportService.ImportedCase;
import io.trailkeep.casefile.CaseImportService.ImportedComment;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CaseImportService}'s imported comments (WP-26 part e, R6 6; WP-30 part b): the legacy time and author kept,
 * the comment rules that stay, and a case's comments imported together written as one by one, on both vendors.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractCaseCommentImportIT {

    private static final Instant OPENED = Instant.parse("2019-03-04T15:00:00Z");
    private static final Instant CLOSED = Instant.parse("2019-06-01T09:30:00Z");
    private static final ImportRun RUN = new ImportRun(77L, 88L);

    @Autowired
    private CaseImportService imports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private long team;
    private long caseType;
    private long analyst;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        analyst = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
    }

    @Test
    void importedCommentKeepsLegacyTimeAndAuthor() {
        CaseNumber number = imports.importCase(RUN, closed()).number();
        Instant written = Instant.parse("2019-04-02T10:15:00Z");

        long unmatched = imports.importComment(
                RUN, new ImportedComment(number, "Synthetic legacy note", null, "Synthetic Legacy Author", written));
        final long matched = imports.importComment(
                RUN, new ImportedComment(number, "Synthetic matched note", analyst, "Seed User " + analyst, written));

        entityManager.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT AUTHOR_ID, AUTHOR_NAME_SNAPSHOT, BODY FROM CASE_COMMENT WHERE ID = ?", unmatched);
        assertThat(row)
                .containsEntry("AUTHOR_ID", null)
                .containsEntry("AUTHOR_NAME_SNAPSHOT", "Synthetic Legacy Author")
                .containsEntry("BODY", "Synthetic legacy note");
        assertThat(jdbc.queryForObject("SELECT CREATED_AT FROM CASE_COMMENT WHERE ID = ?", Timestamp.class, unmatched))
                .isEqualTo(Timestamp.from(written));
        assertThat(id(jdbc.queryForObject("SELECT AUTHOR_ID FROM CASE_COMMENT WHERE ID = ?", Long.class, matched)))
                .isEqualTo(analyst);
        assertThat(searchText(number)).contains("synthetic legacy note", "synthetic matched note");
        assertThat(String.valueOf(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ENTITY_ID = ? AND ACTION = 'COMMENT_IMPORTED'"
                                + " AND ORIGIN = 'IMPORT'",
                        String.class,
                        unmatched)))
                .contains("\"runId\":77", "Synthetic Legacy Author")
                .doesNotContain("Synthetic legacy note");
    }

    /** A description over 20 000 characters continues, escaped by the importer, in a first comment (§6.2). */
    @Test
    void descriptionOverflowContinuesInAnEscapedFirstComment() {
        CaseNumber number = imports.importCase(RUN, open()).number();
        String escapedTail = "\\*the rest of a long description\\* 1\\. as typed";

        long comment = imports.importComment(
                RUN,
                new ImportedComment(number, escapedTail, ImportPeopleService.IMPORTER_ID, "Trailkeep import", OPENED));

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT BODY FROM CASE_COMMENT WHERE ID = ?", String.class, comment))
                .isEqualTo(escapedTail);
    }

    @Test
    void importedCommentsKeepTheCommentRules() {
        CaseNumber number = imports.importCase(RUN, open()).number();
        CaseNumber unknown = new CaseNumber(number.prefix(), number.year(), 9_999_999L);

        for (ImportedComment refused : new ImportedComment[] {
            new ImportedComment(number, " ", null, "Synthetic Legacy Author", OPENED),
            new ImportedComment(number, "x".repeat(CaseComment.MAX_BODY + 1), null, "Synthetic Legacy Author", OPENED),
            new ImportedComment(number, "Synthetic note", null, " ", OPENED),
            new ImportedComment(
                    number, "Synthetic note", null, "Synthetic", Instant.now().plusSeconds(86_400))
        }) {
            assertThatThrownBy(() -> imports.importComment(RUN, refused)).satisfies(kind(Kind.INVALID));
        }
        assertThatThrownBy(() -> imports.importComment(
                        RUN, new ImportedComment(unknown, "Synthetic note", null, "Synthetic", OPENED)))
                .satisfies(kind(Kind.NOT_FOUND));
    }

    /**
     * A case's comments imported together (WP-30 part b) are written as one by one: in order, each audited, the search
     * text the same; one refused comment writes none of them, nor do comments of two cases.
     */
    @Test
    void commentsImportedTogetherAreWrittenAsOneByOne() {
        CaseNumber together = imports.importCase(RUN, closed()).number();
        CaseNumber alone = imports.importCase(RUN, closed()).number();
        Instant written = Instant.parse("2019-04-02T10:15:00Z");
        List<String> bodies = List.of("Synthetic First NOTEΣ", "Synthetic second note", "Synthetic third note");

        List<Long> ids = imports.importComments(
                RUN,
                bodies.stream()
                        .map(body -> new ImportedComment(together, body, null, "Synthetic Legacy Author", written))
                        .toList());
        bodies.forEach(body ->
                imports.importComment(RUN, new ImportedComment(alone, body, null, "Synthetic Legacy Author", written)));

        entityManager.flush();
        assertThat(ids).hasSize(3).isSorted();
        assertThat(jdbc.queryForList(
                        "SELECT BODY FROM CASE_COMMENT WHERE CASE_ID = ? ORDER BY ID",
                        String.class,
                        caseRow(together).get("ID")))
                .isEqualTo(bodies);
        assertThat(jdbc.queryForList(
                        "SELECT ENTITY_ID FROM AUDIT_EVENT WHERE CASE_ID = ? AND ACTION = 'COMMENT_IMPORTED'"
                                + " ORDER BY ID",
                        Long.class,
                        caseRow(together).get("ID")))
                .isEqualTo(ids);
        String appended = "\nsynthetic first noteς\nsynthetic second note\nsynthetic third note";
        assertThat(searchText(together)).endsWith(appended);
        assertThat(searchText(alone)).endsWith(appended);

        ImportedComment good = new ImportedComment(together, "Synthetic fine note", null, "Synthetic", written);
        assertThatThrownBy(() -> imports.importComments(
                        RUN, List.of(good, new ImportedComment(together, " ", null, "Synthetic", written))))
                .satisfies(kind(Kind.INVALID));
        assertThatThrownBy(() -> imports.importComments(
                        RUN, List.of(good, new ImportedComment(alone, "Synthetic note", null, "Synthetic", written))))
                .satisfies(kind(Kind.INVALID))
                .hasMessageContaining("one case");
        entityManager.flush();
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_COMMENT WHERE CASE_ID = ?",
                        Integer.class,
                        caseRow(together).get("ID")))
                .isEqualTo(3);
        assertThat(imports.importComments(RUN, List.of())).isEmpty();
    }

    /** R6 6 is for imported history: a case created in Trailkeep (no legacy ref) takes no imported comment. */
    @Test
    void nativeCaseTakesNoImportedComment() {
        CaseNumber created = new CaseSeed(jdbc).caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);

        assertThatThrownBy(() -> imports.importComment(
                        RUN, new ImportedComment(created, "Synthetic note", null, "Synthetic", OPENED)))
                .satisfies(kind(Kind.INVALID))
                .hasMessageContaining("only an imported case");
    }

    private ImportedCase open() {
        return new ImportedCase(fields(caseType), ref(), "LEG-2019-0042", null, CaseStatus.OPEN, OPENED, null, null);
    }

    private ImportedCase closed() {
        return new ImportedCase(fields(caseType), ref(), null, null, CaseStatus.CLOSED, OPENED, CLOSED, null);
    }

    private CaseFields fields(long type) {
        return new CaseFields(
                type,
                "Synthetic legacy title",
                CasePriority.NORMAL,
                LocalDate.of(2019, 3, 1),
                team,
                analyst,
                "Synthetic legacy subject",
                "Synthetic legacy description",
                Sensitivity.STANDARD);
    }

    private static String ref() {
        return "legacy1:" + UUID.randomUUID();
    }

    private Map<String, Object> caseRow(CaseNumber number) {
        // The service writes through JPA in the test's transaction; JDBC reads see it once flushed.
        entityManager.flush();
        return jdbc.queryForMap("SELECT * FROM CASE_RECORD WHERE CASE_NUMBER = ?", number.toString());
    }

    private String searchText(CaseNumber number) {
        entityManager.flush();
        return String.valueOf(jdbc.queryForObject(
                "SELECT S.SEARCH_TEXT FROM CASE_SEARCH S JOIN CASE_RECORD C ON C.ID = S.CASE_ID"
                        + " WHERE C.CASE_NUMBER = ?",
                String.class,
                number.toString()));
    }

    private static long id(@Nullable Object value) {
        return ((Number) Objects.requireNonNull(value, "column")).longValue();
    }

    private static Consumer<Throwable> kind(Kind kind) {
        return e -> assertThat(((CaseException) e).kind()).isEqualTo(kind);
    }
}
