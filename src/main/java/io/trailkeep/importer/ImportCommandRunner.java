// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Runs the one command a start of the {@code importer} profile was given (deploy/jobs/importer-job.yaml, design §2.1),
 * as the {@code TRAILKEEP_IMPORT} user; the process then ends ({@code TrailkeepApplication}). A failure fails the
 * start, so the Job's pod exits non-zero. {@code status} and the structural {@code validate-mapping} exist so far; the
 * other commands arrive with their PRs (design §12) and are refused until then. Logs carry run ids, codes and counts
 * only.
 */
@Component
@Profile("importer")
class ImportCommandRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ImportCommandRunner.class);

    private final ImportProperties properties;
    private final ImportStatusReader statuses;
    private final ValidateMappingCommand validateMapping;
    private final InstantSource clock;

    ImportCommandRunner(
            ImportProperties properties,
            ImportStatusReader statuses,
            ValidateMappingCommand validateMapping,
            InstantSource clock) {
        this.properties = properties;
        this.statuses = statuses;
        this.validateMapping = validateMapping;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = properties.command();
        if (!"status".equals(command) && !"validate-mapping".equals(command)) {
            throw new IllegalStateException("Unknown or not yet available import command: " + command);
        }
        Long run = properties.run();
        if (run == null) {
            throw new IllegalStateException("import.run is required");
        }
        if ("status".equals(command)) {
            LOG.info("Import status: {}", statuses.status(run, clock.instant()));
            return;
        }
        Path mapping = properties.mapping();
        if (mapping == null) {
            throw new IllegalStateException("import.mapping is required");
        }
        List<MappingProblem> problems = validateMapping.run(run, mapping);
        problems.forEach(problem -> LOG.warn("Import run {} mapping: {} at {}", run, problem.code(), shown(problem)));
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The mapping has " + problems.size() + " problems; run " + run + " lists them as issues");
        }
        LOG.info("Import run {}: mapping structure valid", run);
    }

    /** A lookup's entries are legacy values, so a place inside one is logged as the lookup's name only. */
    private static String shown(MappingProblem problem) {
        String[] parts = problem.pointer().split("/", 4);
        return parts.length == 4 && "lookups".equals(parts[1]) ? "/lookups/" + parts[2] : problem.pointer();
    }
}
