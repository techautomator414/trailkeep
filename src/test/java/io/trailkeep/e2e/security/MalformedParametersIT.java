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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * A malformed query or form string (issue 218) against the real server after a real sign-in, so Tomcat's own parameter
 * parsing, Spring Security's filter chain and the error handling are all in the path. Tomcat refuses a parameter
 * chunk with no name ({@code =x}) or a bad percent-encoding ({@code %zz}) by throwing when the parameters are first
 * read, which was a 500 for whoever read them first. It is a 400, never a 500, for a GET route, a form POST and an
 * htmx route, in the query string and in an urlencoded body, and for every mapped route. The requests are written on
 * a raw socket because no HTTP client will send these strings.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
class MalformedParametersIT {

    private static final KeycloakDevRealm KEYCLOAK = KeycloakDevRealm.start();

    /** The routes mapped when this test was written; fewer means the enumeration broke. */
    private static final int ROUTE_FLOOR = 60;

    /** An empty parameter name, a bad percent-encoding in a value, and one in a name. */
    private static final List<String> MALFORMED = List.of("=x", "a=b&=c", "a=%zz", "%zz=1");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mapping;

    @DynamicPropertySource
    static void provider(DynamicPropertyRegistry registry) {
        KEYCLOAK.register(registry);
    }

    @Test
    void getRouteAnonymousRequestAndFormPostAnswer400ForAMalformedQueryString() throws Exception {
        String session = session();
        for (String malformed : MALFORMED) {
            assertThat(send("GET", "/cases?" + malformed, session, "", false).status())
                    .as("GET /cases?%s", malformed)
                    .isEqualTo(400);
            assertThat(send("GET", "/cases?" + malformed, null, "", false).status())
                    .as("anonymous GET /cases?%s", malformed)
                    .isNotEqualTo(500);
            assertThat(send("POST", "/session/extend?" + malformed, session, "", false)
                            .status())
                    .as("POST /session/extend?%s", malformed)
                    .isEqualTo(400);
        }
    }

    @Test
    void formPostAndHtmxRouteAnswer400ForAMalformedUrlencodedBody() throws Exception {
        String session = session();
        for (String malformed : MALFORMED) {
            assertThat(send("POST", "/session/extend", session, malformed, false)
                            .status())
                    .as("POST body %s", malformed)
                    .isEqualTo(400);
            Raw htmx = send("POST", "/session/extend", session, malformed, true);
            assertThat(htmx.status()).as("htmx POST body %s", malformed).isEqualTo(400);
        }
    }

    @Test
    void theAnswerIsTheErrorPageOrForHtmxTheProblemFragmentAndNeverEchoesTheInput() throws Exception {
        String session = session();
        Raw page = send("GET", "/cases?=SECRETPROBE", session, "", false);
        assertThat(page.status()).isEqualTo(400);
        assertThat(page.body()).contains("<html").doesNotContain("SECRETPROBE");
        Raw fragment = send("GET", "/cases?=SECRETPROBE", session, "", true);
        assertThat(fragment.status()).isEqualTo(400);
        assertThat(fragment.body())
                .contains("role=\"alert\"")
                .doesNotContain("<html")
                .doesNotContain("SECRETPROBE");
    }

    @Test
    void everyMappedRouteAnswers400ForAMalformedQueryStringAndBody() throws Exception {
        String session = session();
        List<Route> routes = Routes.of(mapping);
        assertThat(routes).hasSizeGreaterThanOrEqualTo(ROUTE_FLOOR);
        List<String> wrong = new ArrayList<>();
        for (Route route : routes) {
            for (String path : route.expand(variable -> "number".equals(variable) ? "CASE-2026-000001" : "1")) {
                for (String malformed : List.of("=x", "a=%zz")) {
                    expect400(wrong, route, path + "?" + malformed, "", session, "query " + malformed);
                    if (route.mutating()) {
                        expect400(wrong, route, path, malformed, session, "body " + malformed);
                    }
                }
            }
        }
        assertThat(wrong).as("routes that did not answer 400").isEmpty();
    }

    private static void expect400(
            List<String> wrong, Route route, String target, String body, String session, String how)
            throws IOException {
        int status = send(route.method().name(), target, session, body, false).status();
        if (status != 400) {
            wrong.add(route.method() + " " + target + " (" + how + ") -> " + status + " " + route.handler());
        }
    }

    private static String session() throws IOException, InterruptedException {
        TestBrowser browser = new TestBrowser();
        assertThat(browser.login(KEYCLOAK.app("/"), "alpha.analyst").statusCode())
                .isEqualTo(200);
        return Objects.requireNonNull(browser.cookie("SESSION"));
    }

    private record Raw(int status, String body) {}

    /** One HTTP/1.1 request exactly as written, so a string the client libraries refuse still goes out. */
    private static Raw send(String method, String target, String session, String body, boolean htmx)
            throws IOException {
        URI app = URI.create(KEYCLOAK.app(""));
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder head = new StringBuilder(method + " " + target + " HTTP/1.1\r\n")
                .append("Host: localhost:" + app.getPort() + "\r\n")
                .append("Connection: close\r\n");
        if (session != null) {
            head.append("Cookie: SESSION=" + session + "\r\n");
        }
        if (htmx) {
            head.append("HX-Request: true\r\n");
        }
        if (!body.isEmpty() || !"GET".equals(method)) {
            head.append("Content-Type: application/x-www-form-urlencoded\r\n")
                    .append("Content-Length: " + payload.length + "\r\n");
        }
        head.append("\r\n");
        try (Socket socket = new Socket("localhost", app.getPort())) {
            socket.setSoTimeout(20_000);
            socket.getOutputStream().write(head.toString().getBytes(StandardCharsets.UTF_8));
            socket.getOutputStream().write(payload);
            socket.getOutputStream().flush();
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            socket.getInputStream().transferTo(all);
            String text = all.toString(StandardCharsets.UTF_8);
            int status = Integer.parseInt(text.substring(9, 12));
            int split = text.indexOf("\r\n\r\n");
            return new Raw(status, split < 0 ? "" : text.substring(split + 4));
        }
    }
}
