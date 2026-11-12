// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.TrailkeepApplication;
import io.trailkeep.casefile.CaseCommentService;
import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.document.CaseAttachmentService;
import io.trailkeep.document.ScanStatus;
import io.trailkeep.notification.NotificationService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestDatabaseUsers;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.session.Session;
import org.springframework.session.SessionRepository;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The web application started as the runtime user, which owns no table, with {@code app.db.schema} naming the owner,
 * as an Oracle deployment runs it (BUILD_PLAN §8 rule 7), once per vendor. Hibernate qualifies the tables it maps; this
 * proves every statement it does not write finds its table through the connection's default schema: the case number
 * sequence and the search text (creating a case, posting a comment), the attachment chunks (uploading and downloading),
 * the outbox (a mail queued, then claimed and released by the dispatcher against an unreachable server), ShedLock's
 * {@code SHEDLOCK} and Spring Session's {@code SPRING_SESSION}. Seeded and cleaned up as the owner.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractRuntimeUserIT {

    private static final String TITLE = "Synthetic runtime-user case";
    private static final String LOCK = "runtime-user-it";
    private static final byte[] CONTENT = "Synthetic runtime-user attachment".getBytes(StandardCharsets.US_ASCII);

    @Autowired
    private TestDatabaseUsers users;

    @Autowired
    private JdbcTemplate jdbc;

    @Value("${app.db.app-user}")
    private String appUser = "";

    private @Nullable Long mail;

    /**
     * The profile that points this vendor's test contexts at their container ({@code test} or {@code oracle-test}).
     *
     * @return the profile
     */
    abstract String vendorProfile();

    @Test
    void everyNativeStatementFindsItsTableAsTheRuntimeUser() throws SQLException, IOException {
        CaseSeed seed = new CaseSeed(jdbc);
        long analyst = seed.user();
        long team = seed.team();
        long caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        Path temp = Files.createTempDirectory("tk-runtime-user");
        try (ConfigurableApplicationContext app = start(temp)) {
            Actor actor = new Actor(
                    new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Analyst");

            String number = caseWithComment(app, actor, caseType, team).toString();
            assertThat(jdbc.queryForObject(
                            "SELECT SEARCH_TEXT FROM CASE_SEARCH WHERE CASE_ID ="
                                    + " (SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?)",
                            String.class,
                            number))
                    .contains("zebracorn");

            assertThat(uploadAndDownload(app.getBean(CaseAttachmentService.class), actor, number))
                    .isEqualTo(CONTENT);

            mail = queue(app, number);
            assertThat(attemptsWithin(mail, Duration.ofSeconds(30)))
                    .as("the dispatcher claimed the mail and recorded the failed attempt")
                    .isPositive();

            Optional<SimpleLock> lock = app.getBean(LockProvider.class)
                    .lock(new LockConfiguration(Instant.now(), LOCK, Duration.ofSeconds(30), Duration.ZERO));
            assertThat(lock).isPresent();
            lock.get().unlock();

            roundTrip(app.getBean(JdbcIndexedSessionRepository.class));
        } finally {
            remove(team, caseType, analyst);
            try (Stream<Path> left = Files.list(temp)) {
                assertThat(left).as("upload temp files left behind").isEmpty();
            }
            Files.delete(temp);
        }
    }

    /** Creates a case (the case-number sequence, the search row) and posts a comment (the search text appended). */
    private static CaseNumber caseWithComment(
            ConfigurableApplicationContext app, Actor actor, long caseType, long team) {
        CaseNumber number = app.getBean(CaseService.class)
                .create(
                        actor,
                        new CaseFields(
                                caseType,
                                TITLE,
                                CasePriority.NORMAL,
                                LocalDate.now(ZoneOffset.UTC).minusDays(1),
                                team,
                                actor.permissions().userId(),
                                "Synthetic subject",
                                "Synthetic description",
                                Sensitivity.STANDARD));
        app.getBean(CaseCommentService.class).post(actor, number.toString(), "Synthetic zebracorn comment");
        return number;
    }

    /** Uploads {@link #CONTENT} (the chunk inserts) and downloads it again (the chunk reads). */
    private byte[] uploadAndDownload(CaseAttachmentService attachments, Actor actor, String number) throws IOException {
        assertThat(attachments.upload(actor, number, "synthetic.txt", new ByteArrayInputStream(CONTENT)))
                .isEqualTo(ScanStatus.NOT_SCANNED);
        long attachment = Objects.requireNonNull(jdbc.queryForObject(
                "SELECT ID FROM CASE_ATTACHMENT WHERE CASE_ID = (SELECT ID FROM CASE_RECORD WHERE CASE_NUMBER = ?)",
                Long.class,
                number));
        ByteArrayOutputStream downloaded = new ByteArrayOutputStream();
        attachments.download(actor, number, attachment).writeTo(downloaded);
        return downloaded.toByteArray();
    }

    /** Queues one mail through the outbox, in a transaction of the application's own. */
    private static long queue(ConfigurableApplicationContext app, String number) {
        NotificationService notifications = app.getBean(NotificationService.class);
        return Objects.requireNonNull(new TransactionTemplate(app.getBean(PlatformTransactionManager.class))
                .execute(status -> notifications.enqueue(
                        "owner@example.invalid",
                        "comment-added",
                        Map.of(
                                "caseNumber",
                                number,
                                "title",
                                TITLE,
                                "commenter",
                                "Synthetic Analyst",
                                "link",
                                "https://trailkeep.example.invalid/cases/" + number),
                        null)));
    }

    /** Saves, reads back and deletes one session through Spring Session's repository. */
    private static <S extends Session> void roundTrip(SessionRepository<S> sessions) {
        S session = sessions.createSession();
        session.setAttribute("probe", "synthetic");
        sessions.save(session);
        assertThat(sessions.findById(session.getId())).isNotNull();
        sessions.deleteById(session.getId());
    }

    /** Polls the outbox row's attempt count, as the owner, until it is positive or the time is up. */
    private int attemptsWithin(long mail, Duration limit) {
        Instant until = Instant.now().plus(limit);
        int attempts = 0;
        while (attempts == 0 && Instant.now().isBefore(until)) {
            attempts = Objects.requireNonNull(
                    jdbc.queryForObject("SELECT ATTEMPTS FROM NOTIFICATION_OUTBOX WHERE ID = ?", Integer.class, mail));
            if (attempts == 0) {
                try {
                    Thread.sleep(250);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return attempts;
    }

    /**
     * Starts the web application as the runtime user, as its Deployment would: no migration, {@code validate} on,
     * external scanning (no clamd here), and the dispatcher every second against the test profile's closed mail port.
     */
    private ConfigurableApplicationContext start(Path temp) throws SQLException {
        // Command-line arguments, which outrank the test profile's own values (its closed cron, clamd mode).
        return new SpringApplicationBuilder(TrailkeepApplication.class)
                .web(WebApplicationType.SERVLET)
                .profiles(vendorProfile())
                .run(
                        "--spring.datasource.url=" + users.jdbcUrl(),
                        "--spring.datasource.username=" + appUser,
                        "--spring.datasource.password=" + users.newPassword(appUser),
                        "--spring.flyway.enabled=false",
                        "--spring.jpa.hibernate.ddl-auto=validate",
                        "--app.db.schema=" + users.ownerSchema(),
                        "--server.port=0",
                        "--management.server.port=0",
                        "--app.attachments.scanning=external",
                        "--app.attachments.temp-dir=" + temp,
                        "--app.mail.dispatch-cron=* * * * * *");
    }

    /** Test-only cleanup of everything the test wrote, as the schema owner. */
    private void remove(long team, long caseType, long analyst) {
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = " + team + ")";
        if (mail != null) {
            jdbc.update("DELETE FROM NOTIFICATION_OUTBOX WHERE ID = ?", mail);
        }
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX" + ofTeam);
        jdbc.update("DELETE FROM USER_ALERT" + ofTeam);
        jdbc.update("DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeam
                + ")");
        jdbc.update("DELETE FROM CASE_ATTACHMENT" + ofTeam);
        jdbc.update("DELETE FROM CASE_COMMENT" + ofTeam);
        jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeam);
        jdbc.update("DELETE FROM CASE_SEARCH" + ofTeam);
        jdbc.update("DELETE FROM CASE_SLA" + ofTeam);
        jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
        jdbc.update("DELETE FROM SHEDLOCK WHERE NAME = ?", LOCK);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", analyst);
    }
}
