// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.InstantSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * The reminders tick's cluster-level health ({@link JobHealth}): within {@code app.reminders.health-grace} of its last
 * success, two hourly ticks by default; gauge {@code trailkeep.reminders.age}.
 */
@Component
final class RemindersTickHealthIndicator extends JobHealth {

    RemindersTickHealthIndicator(
            JobRuns runs,
            InstantSource clock,
            @Value("${app.reminders.health-grace:PT2H}") Duration grace,
            MeterRegistry registry) {
        super(RemindersTick.JOB, runs, clock, grace, registry, "trailkeep.reminders.age");
    }
}
