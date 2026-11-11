// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.support.LoggingReset;
import io.trailkeep.testsupport.OidcDiscoveryStub;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The prod profile writes one JSON object per line, carrying the request id from the MDC. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "trailkeep.test.context=json-logging",
            "app.proxy.pattern=10\\.128\\.\\d+\\.\\d+",
            // prod requires the R__grants.sql grantees (no defaults); TestDatabaseUsers creates these.
            "app.db.app-user=trailkeep_app",
            "app.db.purge-user=trailkeep_purge",
            "app.db.import-user=trailkeep_import",
            // prod requires the scanner's host and TLS bundle too (ADR-0012); nothing here uploads, so an unreachable
            // host and the JDK's public CA store will do.
            "app.attachments.clamd.host=127.0.0.1",
            "spring.ssl.bundle.jks.clamd.truststore.location=file:${java.home}/lib/security/cacerts",
            // prod requires the deep-link base, the sender and a mail server (WP-17); nothing here sends.
            "app.base-url=https://trailkeep.example.invalid",
            "app.mail.from=trailkeep@example.invalid",
            "app.mail.dispatch-cron=-",
            "spring.mail.host=127.0.0.1",
            "spring.mail.port=9"
        })
@ActiveProfiles({"prod", "postgresql"})
@Import({RemoteAddressController.class, TestOnlyEndpointsSecurity.class, PostgresTestcontainersConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class JsonLoggingIT {

    @TempDir
    static Path workDir;

    /** prod finds its identity provider by issuer discovery at startup; this IT never logs in. */
    private static final OidcDiscoveryStub IDP = OidcDiscoveryStub.start();

    @BeforeAll
    static void applyThisProfilesLoggingConfiguration() {
        LoggingReset.beforeNewContext();
    }

    @AfterAll
    static void letTheNextContextApplyItsOwn() {
        LoggingReset.beforeNewContext();
        IDP.close();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void writablePaths(DynamicPropertyRegistry registry) {
        // prod points these at emptyDir mounts that do not exist on a build machine.
        registry.add("server.tomcat.basedir", () -> workDir.resolve("tomcat").toString());
        registry.add("spring.servlet.multipart.location", () -> workDir.toString());
        // prod refuses to start without the audit HMAC key (WP-06); generate a throwaway one per run.
        registry.add("app.audit.hmac-key", JsonLoggingIT::randomHmacKey);
        registry.add("app.security.oidc.issuer-uri", IDP::issuer);
        registry.add("app.security.oidc.client-id", () -> "trailkeep");
        registry.add("app.security.oidc.client-secret", () -> "test-only-client-secret");
    }

    private static String randomHmacKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    @Test
    void emitsJsonWithTheRequestId(CapturedOutput output) throws Exception {
        assertThat(get(port, "/test-only/log", "X-Request-Id", "json-req-42").statusCode())
                .isEqualTo(200);

        String line = output.getOut()
                .lines()
                .filter(l -> l.contains(RemoteAddressController.PROBE_MESSAGE))
                .findFirst()
                .orElseThrow();
        JsonNode json = JsonMapper.builder().build().readTree(line);

        assertThat(json.get("message").asString()).isEqualTo(RemoteAddressController.PROBE_MESSAGE);
        assertThat(json.get("level").asString()).isEqualTo("INFO");
        assertThat(json.get("requestId").asString()).isEqualTo("json-req-42");
        assertThat(json.has("@timestamp")).isTrue();
        assertThat(json.has("logger_name")).isTrue();
    }
}
