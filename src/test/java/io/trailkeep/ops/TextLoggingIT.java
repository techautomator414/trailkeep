// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.support.LoggingReset;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/** Outside the JSON profiles, lines stay human-readable and still show the request id. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "trailkeep.test.context=text-logging"})
@ActiveProfiles("test")
@Import({RemoteAddressController.class, PostgresTestcontainersConfiguration.class})
@ExtendWith(OutputCaptureExtension.class)
class TextLoggingIT {

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

    @Test
    void emitsPlainTextWithTheRequestId(CapturedOutput output) throws Exception {
        assertThat(get(port, "/test-only/log", "X-Request-Id", "text-req-7").statusCode())
                .isEqualTo(200);

        String line = output.getOut()
                .lines()
                .filter(l -> l.contains(RemoteAddressController.PROBE_MESSAGE))
                .findFirst()
                .orElseThrow();

        assertThat(line).doesNotStartWith("{").contains("[text-req-7]").contains(" INFO ");
    }
}
