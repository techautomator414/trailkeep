// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.time.InstantSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the one command a start of the {@code importer} profile was given (deploy/jobs/importer-job.yaml, design §2.1),
 * as the {@code TRAILKEEP_IMPORT} user; the process then ends ({@code TrailkeepApplication}). A failure fails the
 * start, so the Job's pod exits non-zero. Only {@code status} exists so far; the other commands arrive with their PRs
 * (design §12) and are refused until then. Logs carry run ids, codes and counts only.
 */
@Component
@Profile("importer")
class ImportCommandRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ImportCommandRunner.class);

    private final ImportProperties properties;
    private final ImportStatusReader statuses;
    private final InstantSource clock;

    ImportCommandRunner(ImportProperties properties, ImportStatusReader statuses, InstantSource clock) {
        this.properties = properties;
        this.statuses = statuses;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = properties.command();
        Long run = properties.run();
        if (!"status".equals(command)) {
            throw new IllegalStateException("Unknown or not yet available import command: " + command);
        }
        if (run == null) {
            throw new IllegalStateException("import.run is required");
        }
        LOG.info("Import status: {}", statuses.status(run, clock.instant()));
    }
}
