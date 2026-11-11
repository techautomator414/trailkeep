// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Conditional;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Whether clamd answers, asked on a schedule by every replica (WP-25 part f, BUILD_PLAN §16 "clamd down"): gauge
 * {@code trailkeep.scanner.up}, 1 after clamd answered {@code VERSION} and 0 after it did not, {@code NaN} before the
 * first probe. Each replica probes its own path to clamd (its NetworkPolicy, its TLS trust), so the alert takes the
 * minimum. The probe is {@link ClamdClient#version()} with its 3-second bound, once a minute by default
 * ({@code app.attachments.clamd.probe-cron}; {@code -} turns it off, as the test profiles do). Uploads are refused
 * while clamd is down, so this lets operators know before a person tries one. It exists only in {@code clamd} mode;
 * with external scanning the gauge is absent.
 */
@Component
@Conditional(ClamdScanningCondition.class)
class ScannerProbe {

    private static final Logger LOG = LoggerFactory.getLogger(ScannerProbe.class);

    private final ClamdClient clamd;
    private final AtomicReference<Double> up = new AtomicReference<>(Double.NaN);

    ScannerProbe(ClamdClient clamd, MeterRegistry registry) {
        this.clamd = clamd;
        Gauge.builder("trailkeep.scanner.up", up, AtomicReference::get)
                .description("1 if clamd answered this replica's last probe, 0 if it did not")
                .register(registry);
    }

    @Scheduled(cron = "${app.attachments.clamd.probe-cron:0 * * * * *}")
    void probe() {
        try {
            clamd.version();
            if (!Double.valueOf(1).equals(up.getAndSet(1.0))) {
                LOG.info("clamd answers");
            }
        } catch (ScannerUnavailableException e) {
            if (!Double.valueOf(0).equals(up.getAndSet(0.0))) {
                LOG.warn("clamd does not answer: uploads are refused until it does");
            }
        }
    }
}
