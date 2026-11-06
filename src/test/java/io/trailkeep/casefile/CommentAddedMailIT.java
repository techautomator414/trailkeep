// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The comment-added mail (WP-17 part b): a comment by someone other than the case's owner queues one mail to the owner
 * in the posting transaction (it commits with the comment and rolls back with it), carrying the case number, title,
 * commenter and a deep link but never the comment, description or subject; the owner's own comment, and a comment on
 * the case of a disabled owner, queue nothing. Seeds and comments are committed and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CommentAddedMailIT {

    private static final String COMMENT = "A synthetic comment whose text never leaves the case";

    @Autowired
    private CaseCommentService comments;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    private CaseSeed seed;
    private long team;
    private long owner;
    private long colleague;
    private CaseNumber number;
    private long caseId;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = seed.user();
        colleague = seed.user();
        seed.member(owner, team, TeamRole.ANALYST);
        seed.member(colleague, team, TeamRole.ANALYST);
        number = seed.caseRow(team, owner, Sensitivity.STANDARD, CaseStatus.OPEN);
        caseId = seed.caseId(number);
    }

    @AfterEach
    void removeCommittedRows() {
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_COMMENT WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_SEARCH WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER WHERE ID IN (?, ?)", owner, colleague);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void someoneElsesCommentQueuesOneMailToTheOwnerWithNumberTitleCommenterAndLink() {
        transactions.executeWithoutResult(status -> comments.post(actor(colleague), number.toString(), COMMENT));

        List<Map<String, Object>> mails = mails();
        assertThat(mails).singleElement().satisfies(mail -> {
            assertThat(mail)
                    .containsEntry("RECIPIENT", "seed" + owner + "@example.test")
                    .containsEntry("TEMPLATE_ID", "comment-added")
                    .containsEntry("STATUS", "PENDING")
                    .containsEntry("SUBJECT", "New comment on " + number);
            String link = "https://trailkeep.example.invalid/cases/" + number + "#comments";
            assertThat(Objects.toString(mail.get("BODY_TEXT")))
                    .contains("Synthetic Colleague commented on case " + number, "Seed case " + caseId, link)
                    .doesNotContain(COMMENT, "Synthetic description");
            assertThat(Objects.toString(mail.get("BODY_HTML")))
                    .contains("<a href=\"" + link + "\">", "Seed case " + caseId)
                    .doesNotContain(COMMENT, "Synthetic description");
        });
    }

    @Test
    void rolledBackCommentTakesItsMailWithIt() {
        transactions.executeWithoutResult(status -> {
            comments.post(actor(colleague), number.toString(), COMMENT);
            entityManager.flush();
            assertThat(mails()).hasSize(1);
            status.setRollbackOnly();
        });

        assertThat(mails()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM CASE_COMMENT WHERE CASE_ID = ?", Long.class, caseId))
                .isZero();
    }

    @Test
    void theOwnersOwnCommentAndACommentForADisabledOwnerQueueNothing() {
        transactions.executeWithoutResult(status -> comments.post(actor(owner), number.toString(), COMMENT));
        assertThat(mails()).isEmpty();

        jdbc.update("UPDATE APP_USER SET DISABLED = ? WHERE ID = ?", true, owner);
        transactions.executeWithoutResult(status -> comments.post(actor(colleague), number.toString(), COMMENT));
        assertThat(mails()).isEmpty();
    }

    private List<Map<String, Object>> mails() {
        return jdbc.queryForList("SELECT * FROM NOTIFICATION_OUTBOX WHERE CASE_ID = ?", caseId);
    }

    private Actor actor(long userId) {
        return new Actor(
                new UserPermissions(userId, false, Set.of(), Map.of(team, TeamRole.ANALYST)),
                userId == owner ? "Synthetic Owner" : "Synthetic Colleague");
    }
}
