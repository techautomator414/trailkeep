// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code CASE_COMMENT} and its scoped repository, once per vendor (WP-13): a comment is stored as written, with its
 * author snapshot, and read back oldest first only through a scope that reads its case; and a body longer than 4000
 * bytes, up to the 20 000-character cap, survives the round trip (Oracle's {@code VARCHAR2} ceiling, A.1 #7).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractCaseCommentIT {

    private static final Instant WRITTEN = Instant.parse("2026-11-04T09:30:00Z");

    private static final String INSERT = "INSERT INTO CASE_COMMENT (ID, CASE_ID, BODY, AUTHOR_ID,"
            + " AUTHOR_NAME_SNAPSHOT, CREATED_AT) VALUES (?, ?, ?, ?, ?, ?)";

    @Autowired
    private CaseCommentRepository comments;

    @Autowired
    private CaseScopeService scopes;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    private CaseSeed seed;
    private long team;
    private long author;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        author = seed.user();
        seed.member(author, team, TeamRole.ANALYST);
    }

    @Test
    void commentsAreStoredAsWrittenAndReadBackOldestFirst() {
        CaseNumber number = seed.caseRow(team, author, Sensitivity.STANDARD, CaseStatus.OPEN);
        long caseId = seed.caseId(number);
        String longBody = "Synthetic **note** with accents: é ü ß. ".repeat(150);
        assertThat(longBody.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(4000);
        entityManager.persist(CaseComment.of(caseId, longBody, author, "Synthetic Author", WRITTEN.plusSeconds(60)));
        entityManager.persist(CaseComment.of(caseId, "First, *short*", author, "Synthetic Author", WRITTEN));
        entityManager.flush();
        entityManager.clear();

        List<CaseComment> read = comments.findForCase(scopes.forUser(analyst(author)), number);

        assertThat(read).extracting(CaseComment::getBody).containsExactly("First, *short*", longBody);
        assertThat(read).allSatisfy(comment -> {
            assertThat(comment.getCaseId()).isEqualTo(caseId);
            assertThat(comment.getAuthorId()).isEqualTo(author);
            assertThat(comment.getAuthorName()).isEqualTo("Synthetic Author");
        });
        assertThat(read.get(0).getCreatedAt()).isEqualTo(WRITTEN);
    }

    @Test
    void theCommentsOfAnUnreadableCaseAreNotThere() {
        long colleague = seed.user();
        seed.member(colleague, team, TeamRole.ANALYST);
        CaseNumber restricted = seed.caseRow(team, author, Sensitivity.RESTRICTED, CaseStatus.OPEN);
        seed.comment(seed.caseId(restricted), author, "A synthetic restricted comment");

        assertThat(comments.findForCase(scopes.forUser(analyst(colleague)), restricted))
                .isEmpty();
        assertThat(comments.findForCase(scopes.forUser(analyst(author)), restricted))
                .hasSize(1);
    }

    @Test
    void theTableTakesABodyAtTheCapThroughPlainJdbc() {
        long caseId = seed.caseId(seed.caseRow(team, author, Sensitivity.STANDARD, CaseStatus.OPEN));
        insert(caseId, "x".repeat(CaseComment.MAX_BODY));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CASE_COMMENT WHERE CASE_ID = ?", Long.class, caseId))
                .isEqualTo(1L);
    }

    private void insert(long caseId, String body) {
        Long id = jdbc.queryForObject(nextCommentId(), Long.class);
        jdbc.update(INSERT, id, caseId, body, author, "Synthetic Author", Timestamp.from(WRITTEN));
    }

    /**
     * The vendor's statement for one value of {@code SEQ_CASE_COMMENT}, which also proves the sequence exists.
     *
     * @return the SQL
     */
    abstract String nextCommentId();

    private UserPermissions analyst(long userId) {
        return new UserPermissions(userId, false, Set.of(), Map.of(team, TeamRole.ANALYST));
    }
}
