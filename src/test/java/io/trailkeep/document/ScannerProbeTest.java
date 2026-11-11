// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * The per-replica clamd probe (WP-25 part f): {@code trailkeep.scanner.up} is {@code NaN} before the first probe, 1
 * while clamd answers {@code VERSION}, 0 when it does not (refused, silent within the 3-second bound, or any other
 * reply). A replica registers the gauge once; each test uses its own registry per probe.
 */
class ScannerProbeTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private static ScanEngineService engine(ClamdClient client) {
        return new DefaultScanEngineService(
                new StaticListableBeanFactory(Map.of("clamd", client)).getBeanProvider(ClamdClient.class));
    }

    private double up() {
        return registry.get("trailkeep.scanner.up").gauge().value();
    }

    @Test
    void theGaugeFollowsWhetherClamdAnswers() throws IOException {
        try (FakeClamd clamd = FakeClamd.answering(bytes -> "ClamAV 1.4.3/27456/Mon Nov 10 08:00:00 2026")) {
            ScannerProbe probe = new ScannerProbe(engine(ClamdClientTest.client(clamd.port())), registry);
            assertThat(up()).isNaN();

            probe.probe();
            assertThat(up()).isEqualTo(1.0);
        }
        SimpleMeterRegistry other = new SimpleMeterRegistry();
        new ScannerProbe(engine(ClamdClientTest.client(ClamdClientTest.closedPort())), other).probe();
        assertThat(other.get("trailkeep.scanner.up").gauge().value()).isEqualTo(0.0);
    }

    @Test
    void silentOrWrongClamdIsDownWithinTheStatusBound() throws IOException {
        try (FakeClamd silent = FakeClamd.answering(bytes -> null);
                FakeClamd wrong = FakeClamd.answering(bytes -> "UNKNOWN COMMAND")) {
            ScannerProbe probe = new ScannerProbe(
                    engine(ClamdClientTest.client(silent.port(), Duration.ofSeconds(30), Duration.ofMinutes(1))),
                    registry);
            long start = System.nanoTime();
            probe.probe();
            assertThat(up()).isEqualTo(0.0);
            assertThat(Duration.ofNanos(System.nanoTime() - start))
                    .isLessThan(Duration.ofSeconds(ClamdClient.STATUS_SECONDS + 2));

            SimpleMeterRegistry other = new SimpleMeterRegistry();
            new ScannerProbe(engine(ClamdClientTest.client(wrong.port())), other).probe();
            assertThat(other.get("trailkeep.scanner.up").gauge().value()).isEqualTo(0.0);
        }
    }
}
