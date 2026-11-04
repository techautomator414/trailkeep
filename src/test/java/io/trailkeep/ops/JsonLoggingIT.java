// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.support.LoggingReset;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import java.nio.file.Path;
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
            "app.db.purge-user=trailkeep_purge"
        })
@ActiveProfiles({"prod", "postgresql"})
@Import({RemoteAddressController.class, PostgresTestcontainersConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class JsonLoggingIT {

    @TempDir
    static Path workDir;

    @BeforeAll
    static void applyThisProfilesLoggingConfiguration() {
        LoggingReset.beforeNewContext();
    }

    @AfterAll
    static void letTheNextContextApplyItsOwn() {
        LoggingReset.beforeNewContext();
    }

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void writablePaths(DynamicPropertyRegistry registry) {
        // prod points these at emptyDir mounts that do not exist on a build machine.
        registry.add("server.tomcat.basedir", () -> workDir.resolve("tomcat").toString());
        registry.add("spring.servlet.multipart.location", () -> workDir.toString());
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
