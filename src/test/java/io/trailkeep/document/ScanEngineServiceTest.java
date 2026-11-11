// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.document.ScanEngineService.State;
import io.trailkeep.document.ScanEngineService.Status;
import java.io.IOException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The scanner's status for the System screen (WP-25): clamd's {@code VERSION} line over the same TLS exchange as a
 * scan, anything else is unavailable, and external scanning calls nothing.
 */
class ScanEngineServiceTest {

    private static ScanEngineService service(Map<String, Object> beans) {
        return new DefaultScanEngineService(new StaticListableBeanFactory(beans).getBeanProvider(ClamdClient.class));
    }

    @Test
    void clamdThatAnswersItsVersionIsAvailable() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "ClamAV 1.4.3/27456/Mon Nov 10 08:00:00 2026")) {
            ClamdClient client = ClamdClientTest.client(clamd.port());

            assertThat(service(Map.of("clamd", client)).status())
                    .isEqualTo(new Status(State.AVAILABLE, "ClamAV 1.4.3/27456/Mon Nov 10 08:00:00 2026"));
            assertThat(clamd.commands).containsExactly("zVERSION");
            assertThat(clamd.chunkLengths).isEmpty();
        }
    }

    @Test
    void clamdThatAnswersAnythingElseOrNothingIsUnavailable() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "UNKNOWN COMMAND")) {
            ClamdClient client = ClamdClientTest.client(clamd.port());

            assertThatThrownBy(client::version).isInstanceOf(ScannerUnavailableException.class);
            assertThat(service(Map.of("clamd", client)).status()).isEqualTo(new Status(State.UNAVAILABLE, null));
        }
        ClamdClient refused = ClamdClientTest.client(ClamdClientTest.closedPort());
        assertThat(service(Map.of("clamd", refused)).status()).isEqualTo(new Status(State.UNAVAILABLE, null));
    }

    @Test
    void externalScanningHasNoClientAndCallsNothing() {
        assertThat(service(Map.of()).status()).isEqualTo(new Status(State.EXTERNAL, null));
    }
}
