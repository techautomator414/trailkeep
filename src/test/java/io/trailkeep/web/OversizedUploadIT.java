// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.casefile.CaseService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestBrowser;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A file over {@code spring.servlet.multipart.max-file-size} on a real Tomcat (MockMvc does not enforce the limit):
 * the container refuses the part before any controller runs, and the person still gets the case page with 422 and the
 * "too large" error summary, not a generic error page; nothing is stored. A Reader sending the same file gets 403,
 * and an unreadable case 404, so the limit answers no earlier than the case checks. The server runs on a port chosen
 * up front ({@code DEFINED_PORT}), because the dev realm's redirect URIs name it; it is a real Tomcat all the same.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT,
        properties = {"app.attachments.scanning=external", "app.attachments.max-bytes=1MB"})
class OversizedUploadIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String ALPHA_ANALYST = "00000000-0000-4000-8000-000000000002";
    private static final String ALPHA_READER = "00000000-0000-4000-8000-000000000001";
    private static final Pattern CSRF = Pattern.compile("name=\"_csrf\" value=\"([^\"]+)\"");
    private static final String BOUNDARY = "SyntheticBoundary7d1c";

    @Autowired
    private CaseService cases;

    @Autowired
    private JdbcTemplate jdbc;

    private long team;
    private long otherTeam;
    private long caseType;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @AfterEach
    void removeCommittedRows() {
        // Test-only cleanup as the schema owner; the application user may not delete attachments or audit events.
        if (team == 0) {
            return;
        }
        String ofTeam = " WHERE CASE_ID IN (SELECT ID FROM CASE_RECORD WHERE TEAM_ID = ?)";
        for (long id : List.of(team, otherTeam)) {
            jdbc.update(
                    "DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT" + ofTeam
                            + ")",
                    id);
            jdbc.update("DELETE FROM CASE_ATTACHMENT" + ofTeam, id);
            jdbc.update("DELETE FROM AUDIT_EVENT" + ofTeam, id);
            jdbc.update("DELETE FROM CASE_SEARCH" + ofTeam, id);
            jdbc.update("DELETE FROM CASE_SLA" + ofTeam, id);
            jdbc.update("DELETE FROM CASE_RECORD WHERE TEAM_ID = ?", id);
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", id);
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", id);
        }
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void fileOverTheContainerLimitGetsTheCasePageWithTooLarge() throws Exception {
        TestBrowser browser = new TestBrowser();
        browser.login(KEYCLOAK.app("/"), "alpha.analyst");
        long analyst = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_ANALYST));
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        seed.member(analyst, team, TeamRole.ANALYST);
        CaseNumber number = cases.create(
                new CaseService.Actor(
                        new UserPermissions(analyst, false, Set.of(), Map.of(team, TeamRole.ANALYST)),
                        "Synthetic Analyst"),
                new CaseFields(
                        caseType,
                        "Synthetic oversized upload case",
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC).minusDays(1),
                        team,
                        analyst,
                        "Synthetic subject",
                        "Synthetic description",
                        Sensitivity.STANDARD));
        String session = Objects.requireNonNull(browser.cookie("SESSION"));
        HttpResponse<String> page = browser.get(KEYCLOAK.app("/cases/" + number));
        Matcher token = CSRF.matcher(page.body());
        assertThat(token.find()).isTrue();

        HttpResponse<String> response =
                upload(KEYCLOAK.app("/cases/" + number + "/attachments"), session, token.group(1), big());
        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("The file was not uploaded", "no larger than the maximum size");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM CASE_ATTACHMENT WHERE CASE_ID = ?", Long.class, seed.caseId(number)))
                .isZero();

        otherTeam = seed.team();
        CaseNumber unreadable = seed.caseRow(otherTeam, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);
        assertThat(upload(KEYCLOAK.app("/cases/" + unreadable + "/attachments"), session, token.group(1), big())
                        .statusCode())
                .isEqualTo(404);

        TestBrowser readerBrowser = new TestBrowser();
        readerBrowser.login(KEYCLOAK.app("/"), "alpha.reader");
        long reader = Objects.requireNonNull(
                jdbc.queryForObject("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, ALPHA_READER));
        seed.member(reader, team, TeamRole.READER);
        Matcher readerToken =
                CSRF.matcher(readerBrowser.get(KEYCLOAK.app("/cases/" + number)).body());
        assertThat(readerToken.find()).isTrue();
        String readerSession = Objects.requireNonNull(readerBrowser.cookie("SESSION"));
        assertThat(upload(KEYCLOAK.app("/cases/" + number + "/attachments"), readerSession, readerToken.group(1), big())
                        .statusCode())
                .isEqualTo(403);
    }

    /** Half as large again as the 1 MiB cap here. */
    private static byte[] big() {
        return new byte[3 * 512 * 1024];
    }

    private static HttpResponse<String> upload(String url, String session, String csrf, byte[] content)
            throws Exception {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String head = "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + csrf + "\r\n"
                + "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"big.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n";
        body.write(head.getBytes(StandardCharsets.US_ASCII));
        body.write(content);
        body.write(("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        HttpClient client =
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).build();
        return client.send(
                HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Cookie", "SESSION=" + session)
                        .header("Accept", "text/html,application/xhtml+xml")
                        .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
