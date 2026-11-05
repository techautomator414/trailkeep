// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code CASE_SEARCH} maintenance, once per vendor (WP-13, ADR-0006), with committed transactions because locking is
 * the point: a comment over 4000 bytes, and one at the 20 000-character cap in two-byte characters, append through
 * the character-stream bind; an edit after a comment rebuilds the text with the comment in it; two comments posted
 * at once both land, the second waiting on the first's row lock; and an edit that waits on a comment's lock rebuilds
 * with that comment. Each test uses two worker transactions at most, within the four-connection test pool, and
 * removes what it committed.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractCaseSearchIT {

    private static final String DESCRIPTION = "Synthetic searchable description";

    @Autowired
    private CaseService cases;

    @Autowired
    private CaseCommentService comments;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private final ExecutorService workers = Executors.newFixedThreadPool(2);
    private final List<Long> users = new ArrayList<>();
    private long team;
    private long caseType;
    private Actor analyst;
    private CaseNumber number;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        long analystId = seed.user();
        users.add(analystId);
        seed.member(analystId, team, TeamRole.ANALYST);
        analyst = new Actor(
                new UserPermissions(analystId, false, Set.of(), Map.of(team, TeamRole.ANALYST)),
                "Synthetic Search Analyst");
        CaseFields fields = new CaseFields(
                caseType,
                "Synthetic searchable title",
                CasePriority.NORMAL,
                LocalDate.now(ZoneOffset.UTC).minusDays(1),
                team,
                analystId,
                "Synthetic subject never searched",
                DESCRIPTION,
                Sensitivity.STANDARD);
        number = Objects.requireNonNull(transactions.execute(status -> cases.create(analyst, fields)));
    }

    @AfterEach
    void removeCommittedRows() {
        workers.shutdownNow();
        long caseId = caseId();
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_COMMENT WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_SEARCH WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_SLA WHERE CASE_ID = ?", caseId);
        jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        for (long user : users) {
            jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user);
        }
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void commentsOverFourThousandBytesAreAppended() {
        String overVarcharLimit = "Synthetic note with accents: é ü ß. ".repeat(150);
        assertThat(overVarcharLimit.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(4000);
        String atTheCap = "é".repeat(CaseComment.MAX_BODY);

        post(overVarcharLimit);
        post(atTheCap);

        assertThat(searchText())
                .isEqualTo("Synthetic searchable title\n" + DESCRIPTION + "\n" + overVarcharLimit + "\n" + atTheCap);
    }

    @Test
    void editsAfterACommentKeepTheCommentSearchable() {
        post("Synthetic comment with a *findable* word");

        edit("Synthetic edited title");

        assertThat(searchText())
                .isEqualTo("Synthetic edited title\n" + DESCRIPTION + "\nSynthetic comment with a *findable* word");
    }

    @Test
    void twoConcurrentCommentsBothAppear() throws Exception {
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        final Future<?> first = inTransaction(() -> {
            comments.post(analyst, number.toString(), "First concurrent synthetic comment");
            appended.countDown();
            await(release);
        });
        assertThat(appended.await(30, TimeUnit.SECONDS)).isTrue();
        Future<?> second =
                inTransaction(() -> comments.post(analyst, number.toString(), "Second concurrent synthetic comment"));

        // The second append waits on the row lock the first holds until it commits.
        assertThatThrownBy(() -> second.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        first.get(30, TimeUnit.SECONDS);
        second.get(30, TimeUnit.SECONDS);

        assertThat(searchText()).endsWith("\nFirst concurrent synthetic comment\nSecond concurrent synthetic comment");
    }

    @Test
    void anEditWaitingOnACommentRebuildsWithThatComment() throws Exception {
        int version = cases.editable(analyst, number.toString()).version();
        CountDownLatch appended = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        final Future<?> comment = inTransaction(() -> {
            comments.post(analyst, number.toString(), "Synthetic comment written during an edit");
            appended.countDown();
            await(release);
        });
        assertThat(appended.await(30, TimeUnit.SECONDS)).isTrue();
        Future<?> edit = inTransaction(() -> cases.edit(analyst, number.toString(), version, changes("Raced title")));

        // The rebuild waits on SELECT … FOR UPDATE, then reads the comment that committed meanwhile.
        assertThatThrownBy(() -> edit.get(1, TimeUnit.SECONDS)).isInstanceOf(TimeoutException.class);
        release.countDown();
        comment.get(30, TimeUnit.SECONDS);
        edit.get(30, TimeUnit.SECONDS);

        assertThat(searchText())
                .isEqualTo("Raced title\n" + DESCRIPTION + "\nSynthetic comment written during an edit");
    }

    private void post(String body) {
        transactions.executeWithoutResult(status -> comments.post(analyst, number.toString(), body));
    }

    private void edit(String title) {
        int version = cases.editable(analyst, number.toString()).version();
        transactions.executeWithoutResult(status -> cases.edit(analyst, number.toString(), version, changes(title)));
    }

    private Future<?> inTransaction(Runnable work) {
        return workers.submit(() -> transactions.executeWithoutResult(status -> work.run()));
    }

    private String searchText() {
        return Objects.requireNonNull(
                jdbc.queryForObject("SELECT SEARCH_TEXT FROM CASE_SEARCH WHERE CASE_ID = ?", String.class, caseId()));
    }

    private long caseId() {
        return new CaseSeed(jdbc).caseId(number);
    }

    private static CaseChanges changes(String title) {
        return new CaseChanges(title, CasePriority.NORMAL, "Synthetic subject never searched", DESCRIPTION);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the test never released the transaction");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
