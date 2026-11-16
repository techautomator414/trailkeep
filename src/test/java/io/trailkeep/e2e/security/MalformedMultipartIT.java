// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.e2e.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.e2e.security.Routes.Route;
import io.trailkeep.testsupport.KeycloakDevRealm;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.TestBrowser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A malformed or truncated multipart body (issue 234) against the real server after a real sign-in, on raw sockets
 * because no HTTP client sends a body that stops before its closing boundary. Tomcat's multipart parse failure reaches
 * Spring as a {@code MultipartException}, which nothing mapped, so it was a 500 (and an ERROR with a stack trace in the
 * log, 113 times per identity in the API scan). It is a 400 on the application's error page, the input is never
 * echoed, and nothing is logged as a failure. A part over the size limit is not a malformed body: it stays the 413 it
 * always was ({@code OversizedUploadIT}), and a well-formed upload is unchanged.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ExtendWith(OutputCaptureExtension.class)
class MalformedMultipartIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();
    private static final String BOUNDARY = "SyntheticBoundary9c41";
    private static final Pattern CSRF_TOKEN = Pattern.compile("<meta name=\"tk-csrf-token\" content=\"([^\"]+)\">");

    /** The routes mapped when this test was written; fewer means the enumeration broke. */
    private static final int ROUTE_FLOOR = 60;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @Test
    void bodyThatStopsBeforeItsClosingBoundaryIsA400OnTheErrorPageAndNothingIsEchoed(CapturedOutput output)
            throws Exception {
        Session session = session();
        Raw truncated = post(
                "/cases/CASE-2026-000001/attachments",
                session,
                "multipart/form-data; boundary=" + BOUNDARY,
                partsWithoutClosing(session.token(), "SECRETPROBE"));
        assertThat(truncated.status()).isEqualTo(400);
        assertThat(truncated.body()).contains("<html").doesNotContain("SECRETPROBE");
        assertThat(output.getAll()).doesNotContain("MultipartException").doesNotContain("Request processing failed");
    }

    @Test
    void everyKindOfBrokenMultipartBodyIsA400() throws Exception {
        Session session = session();
        String type = "multipart/form-data; boundary=" + BOUNDARY;
        String token = session.token();
        String[][] broken = {
            {type, partsWithoutClosing(token, "x")},
            {type, "--" + BOUNDARY + "\r\nContent-Disposition: form-data; na"},
            {type, "--" + BOUNDARY + "\r\n\r\n"},
            {"multipart/form-data", "--" + BOUNDARY + "\r\n\r\nx\r\n--" + BOUNDARY + "--\r\n"},
            {"multipart/form-data; boundary=", "x"},
        };
        List<String> wrong = new ArrayList<>();
        for (String[] body : broken) {
            int status = post("/cases/CASE-2026-000001/attachments", session, body[0], body[1])
                    .status();
            if (status != 400) {
                wrong.add("content type [" + body[0] + "], body [" + body[1].replace("\r\n", "|") + "] -> " + status);
            }
        }
        assertThat(wrong).as("broken multipart bodies that did not answer 400").isEmpty();
    }

    /**
     * The token in a part, as a plain form submits it: Spring Security reads it first, which makes Tomcat parse the
     * body and answer 400 itself. The token in the header, as htmx and a scanner send it, leaves the first parse to the
     * dispatcher, where nothing answered it (the 500 of the issue).
     */
    @Test
    void theTokenInAPartIsA400TooAndAWellFormedBodyIsNotRefused() throws Exception {
        Session session = session();
        String type = "multipart/form-data; boundary=" + BOUNDARY;
        String body = partsWithoutClosing(session.token(), "x");
        assertThat(post("/cases/CASE-2026-000001/attachments", session, type, body, body.length(), false, false)
                        .status())
                .isEqualTo(400);
        String whole = body + "\r\n--" + BOUNDARY + "--\r\n";
        assertThat(post("/cases/CASE-2026-000001/attachments", session, type, whole, whole.length(), false, false)
                        .status())
                .as("a well-formed body for a case nobody can read")
                .isEqualTo(404);
    }

    @Test
    void clientThatClosesItsSideMidBodyGetsA400WhenItReadsTheAnswer() throws Exception {
        Session session = session();
        String body = partsWithoutClosing(session.token(), "x".repeat(200));
        Raw aborted = post(
                "/cases/CASE-2026-000001/attachments",
                session,
                "multipart/form-data; boundary=" + BOUNDARY,
                body,
                body.length() + 5_000,
                true,
                true);
        assertThat(aborted.status()).isEqualTo(400);
    }

    @Test
    void everyMappedPostRouteAnswersABrokenMultipartBodyWithoutAServerError(CapturedOutput output) throws Exception {
        Session session = session();
        List<Route> routes = Routes.of(mapping).stream()
                .filter(route -> route.method() == RequestMethod.POST)
                .toList();
        assertThat(Routes.of(mapping)).hasSizeGreaterThanOrEqualTo(ROUTE_FLOOR);
        List<String> wrong = new ArrayList<>();
        for (Route route : routes) {
            for (String path : route.expand(variable -> "number".equals(variable) ? "CASE-2026-000001" : "1")) {
                int status = post(
                                path,
                                session,
                                "multipart/form-data; boundary=" + BOUNDARY,
                                partsWithoutClosing(session.token(), "x"))
                        .status();
                if (status >= 500) {
                    wrong.add("POST " + path + " -> " + status + " " + route.handler());
                }
            }
        }
        assertThat(wrong).as("routes that answered a server error").isEmpty();
        assertThat(output.getAll()).doesNotContain("MultipartException").doesNotContain("Request processing failed");
    }

    private static String partsWithoutClosing(String token, String content) {
        return "--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"_csrf\"\r\n\r\n" + token + "\r\n--"
                + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.txt\"\r\n"
                + "Content-Type: text/plain\r\n\r\n" + content;
    }

    private record Session(String cookie, String token) {}

    private static Session session() throws IOException, InterruptedException {
        TestBrowser browser = new TestBrowser();
        assertThat(browser.login(KEYCLOAK.app("/"), "alpha.analyst").statusCode())
                .isEqualTo(200);
        Matcher token = CSRF_TOKEN.matcher(browser.get(KEYCLOAK.app("/")).body());
        assertThat(token.find()).as("the layout's CSRF token").isTrue();
        return new Session(Objects.requireNonNull(browser.cookie("SESSION")), token.group(1));
    }

    private record Raw(int status, String body) {}

    /** One HTTP/1.1 POST exactly as written, with the content length of the body as given (it ends where it ends). */
    private static Raw post(String target, Session session, String contentType, String body) throws IOException {
        return post(target, session, contentType, body, body.getBytes(StandardCharsets.UTF_8).length, false, true);
    }

    /**
     * As above, but declaring {@code declaredLength} bytes; with {@code halfClose} the client then closes its sending
     * side, as a scanner that aborts a request does, so the server meets the end of the stream mid-body.
     */
    private static Raw post(
            String target,
            Session session,
            String contentType,
            String body,
            int declaredLength,
            boolean halfClose,
            boolean tokenInHeader)
            throws IOException {
        URI app = URI.create(KEYCLOAK.app(""));
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        String head = "POST " + target + " HTTP/1.1\r\nHost: localhost:" + app.getPort() + "\r\n"
                + "Accept: text/html,application/xhtml+xml;q=0.9,*/*;q=0.8\r\nConnection: close\r\n"
                + "Cookie: SESSION=" + session.cookie() + "\r\nContent-Type: " + contentType + "\r\n"
                + (tokenInHeader ? "X-CSRF-TOKEN: " + session.token() + "\r\n" : "")
                + "Content-Length: " + declaredLength + "\r\n\r\n";
        try (Socket socket = new Socket("localhost", app.getPort())) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(head.getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            if (halfClose) {
                socket.shutdownOutput();
            }
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int split = text.indexOf("\r\n\r\n");
            return new Raw(Integer.parseInt(text.substring(9, 12)), split < 0 ? "" : text.substring(split + 4));
        }
    }
}
