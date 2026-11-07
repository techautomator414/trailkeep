// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The dispatcher against a real SMTP server, Mailpit (the image {@code compose.yaml} runs, WP-17): one queued mail
 * arrives exactly once, from {@code app.mail.from}, with its subject, a plain-text and an HTML part and the deep link
 * in both, and its row is {@code SENT} after one attempt; a second pass sends nothing.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MailpitDispatchIT {

    /** Mailpit v1.33.0, pinned by the digest {@code compose.yaml} uses. */
    static final DockerImageName IMAGE = DockerImageName.parse(
            "axllent/mailpit@sha256:9b3a463be15354c0f0eba91697d80e3e0d8a5623ef0e627cacc836bc17fd5231");

    private static final int SMTP = 1025;
    private static final int API = 8025;
    private static final String LINK = "https://trailkeep.example.invalid/cases/CASE-2026-000042#comments";
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(IMAGE).withExposedPorts(SMTP, API);

    private final HttpClient http = HttpClient.newHttpClient();
    private final JsonMapper json = JsonMapper.builder().build();

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private NotificationService notifications;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void mailServer(DynamicPropertyRegistry registry) {
        MAILPIT.start();
        registry.add("spring.mail.host", MAILPIT::getHost);
        registry.add("spring.mail.port", () -> MAILPIT.getMappedPort(SMTP));
    }

    @BeforeEach
    @AfterEach
    void emptyBoth() throws Exception {
        jdbc.update("DELETE FROM NOTIFICATION_OUTBOX");
        http.send(request("/api/v1/messages").DELETE().build(), HttpResponse.BodyHandlers.discarding());
    }

    @Test
    void oneQueuedMailArrivesOnceWithItsSubjectBothPartsAndTheDeepLink() throws Exception {
        long id = Objects.requireNonNull(transactions.execute(status -> notifications.enqueue(
                "owner@example.invalid",
                "comment-added",
                Map.of(
                        "caseNumber", "CASE-2026-000042",
                        "title", "Synthetic <title>",
                        "commenter", "Synthetic Commenter",
                        "link", LINK),
                null)));

        assertThat(dispatcher.dispatch()).isEqualTo(1);
        assertThat(dispatcher.dispatch()).isZero();

        JsonNode list = get("/api/v1/messages");
        assertThat(list.get("total").asInt()).isEqualTo(1);
        JsonNode message =
                get("/api/v1/message/" + list.get("messages").get(0).get("ID").asString());
        assertThat(message.get("Subject").asString()).isEqualTo("New comment on CASE-2026-000042");
        assertThat(message.get("From").get("Address").asString()).isEqualTo("trailkeep@example.invalid");
        assertThat(message.get("To").get(0).get("Address").asString()).isEqualTo("owner@example.invalid");
        assertThat(message.get("Text").asString())
                .contains("Synthetic Commenter commented on case CASE-2026-000042, \"Synthetic <title>\".", LINK);
        assertThat(message.get("HTML").asString())
                .contains("<html lang=\"en\">", "Synthetic &lt;title&gt;", "<a href=\"" + LINK + "\">");
        assertThat(jdbc.queryForMap("SELECT * FROM NOTIFICATION_OUTBOX WHERE ID = ?", id))
                .containsEntry("STATUS", "SENT")
                .containsEntry("ATTEMPTS", 1)
                .containsEntry("LOCKED_UNTIL", null)
                .containsEntry("LAST_ERROR", null)
                .doesNotContainEntry("SENT_AT", null);
    }

    @Test
    void theSlaMailsArriveWithTheNumberTitleDueDateAndLink() throws Exception {
        String link = "https://trailkeep.example.invalid/cases/CASE-2026-000043";
        Map<String, String> model = Map.of(
                "caseNumber", "CASE-2026-000043",
                "title", "Synthetic SLA case",
                "due", "2026-12-01",
                "link", link);
        transactions.executeWithoutResult(status -> {
            notifications.enqueue("owner@example.invalid", "sla-at-risk", model, null);
            notifications.enqueue("manager@example.invalid", "sla-overdue", model, null);
        });

        assertThat(dispatcher.dispatch()).isEqualTo(2);

        Map<String, JsonNode> bySubject = new HashMap<>();
        for (JsonNode listed : get("/api/v1/messages").get("messages")) {
            JsonNode message = get("/api/v1/message/" + listed.get("ID").asString());
            bySubject.put(message.get("Subject").asString(), message);
        }
        assertThat(bySubject)
                .containsOnlyKeys(
                        "At risk: CASE-2026-000043 is due on 2026-12-01",
                        "Overdue: CASE-2026-000043 was due on 2026-12-01");
        JsonNode atRisk = Objects.requireNonNull(bySubject.get("At risk: CASE-2026-000043 is due on 2026-12-01"));
        JsonNode overdue = Objects.requireNonNull(bySubject.get("Overdue: CASE-2026-000043 was due on 2026-12-01"));
        assertThat(atRisk.get("Text").asString())
                .contains("\"Synthetic SLA case\", is at risk of missing its SLA: it is due on 2026-12-01.", link);
        assertThat(overdue.get("HTML").asString())
                .contains("Synthetic SLA case", "was due on <span>2026-12-01</span>", "<a href=\"" + link + "\">");
    }

    private JsonNode get(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request(path).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body());
    }

    private static HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(
                URI.create("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(API) + path));
    }
}
