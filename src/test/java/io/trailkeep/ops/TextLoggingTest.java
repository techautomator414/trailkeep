// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.support.HttpProbe.get;
import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.support.LoggingReset;
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
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "trailkeep.test.context=text-logging"})
@ActiveProfiles("test")
@Import(RemoteAddressController.class)
@ExtendWith(OutputCaptureExtension.class)
class TextLoggingTest {

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
