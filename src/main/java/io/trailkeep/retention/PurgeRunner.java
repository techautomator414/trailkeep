// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import java.time.InstantSource;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the purge once when the application starts with the {@code purge} profile: the retention {@code CronJob}
 * (deploy/base/purge-cronjob.yaml) runs the same image with {@code prod,purge}, no web server and the {@code
 * TRAILKEEP_PURGE} credential (BUILD_PLAN §9 "Separation of duties"). The profile starts no scheduler, so the process
 * ends when this returns; a failure fails the start and the pod exits non-zero, and the next schedule tries again.
 */
@Component
@Profile("purge")
class PurgeRunner implements ApplicationRunner {

    private final PurgeJob job;
    private final InstantSource clock;

    PurgeRunner(PurgeJob job, InstantSource clock) {
        this.job = job;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        job.run(clock.instant());
    }
}
