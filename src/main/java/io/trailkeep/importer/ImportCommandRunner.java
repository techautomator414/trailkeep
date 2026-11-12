// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * start, so the Job's pod exits non-zero. {@code status}, {@code validate-mapping}, {@code extract}, {@code dry-run}
 * and {@code purge-staging} exist so far; the other commands arrive with their PRs (design §12) and are refused until
 * then. Every command first applies the staging TTL. Logs carry run ids, codes and counts only.
 */
@Component
@Profile("importer")
class ImportCommandRunner implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ImportCommandRunner.class);

    private final ImportProperties properties;
    private final ImportStatusReader statuses;
    private final ValidateMappingCommand validateMapping;
    private final ExtractCommand extract;
    private final DryRunCommand dryRun;
    private final StagingPurger purger;
    private final InstantSource clock;

    ImportCommandRunner(
            ImportProperties properties,
            ImportStatusReader statuses,
            ValidateMappingCommand validateMapping,
            ExtractCommand extract,
            DryRunCommand dryRun,
            StagingPurger purger,
            InstantSource clock) {
        this.properties = properties;
        this.statuses = statuses;
        this.validateMapping = validateMapping;
        this.extract = extract;
        this.dryRun = dryRun;
        this.purger = purger;
        this.clock = clock;
    }

    @Override
    public void run(ApplicationArguments args) {
        String command = properties.command();
        if (!List.of("status", "validate-mapping", "extract", "dry-run", "purge-staging")
                .contains(command)) {
            throw new IllegalStateException("Unknown or not yet available import command: " + command);
        }
        Long run = properties.run();
        if (run == null) {
            throw new IllegalStateException("import.run is required");
        }
        // Every command first applies the staging TTL (design §5), whichever run it was started for.
        int expired = purger.purgeExpired(clock.instant());
        if (expired > 0) {
            LOG.info("Staging purged for {} expired import runs", expired);
        }
        long runId = run;
        if ("validate-mapping".equals(command)) {
            validateMapping(runId);
            return;
        }
        if ("extract".equals(command)) {
            LOG.info("Import run {}: {} rows staged", runId, extract.run(runId, mapping()));
        }
        if ("dry-run".equals(command)) {
            DryRunCommand.Outcome outcome = dryRun.run(runId, mapping());
            LOG.info(
                    "Import run {}: {} cases checked, {} issues, {} FATAL",
                    runId,
                    outcome.cases(),
                    outcome.issues(),
                    outcome.fatal());
        }
        if ("purge-staging".equals(command)) {
            purger.purge(runId, clock.instant());
            LOG.info("Import run {}: staging purged", runId);
        }
        LOG.info("Import status: {}", statuses.status(runId, clock.instant()));
    }

    private Path mapping() {
        Path mapping = properties.mapping();
        if (mapping == null) {
            throw new IllegalStateException("import.mapping is required");
        }
        return mapping;
    }

    private void validateMapping(long run) {
        List<MappingProblem> problems = validateMapping.run(run, mapping());
        for (MappingProblem problem : problems) {
            LOG.warn("Import run {} mapping: {} at {}", run, problem.code().name(), shown(problem));
        }
        if (!problems.isEmpty()) {
            throw new IllegalStateException(
                    "The mapping has " + problems.size() + " problems; run " + run + " lists them as issues");
        }
        LOG.info("Import run {}: mapping structure valid", run);
    }

    /**
     * The place as logged. A lookup's entries are legacy values, so a place inside one is the lookup's name only, and
     * each key is URL-encoded, so a key with a line break (YAML allows one) cannot forge a log line.
     */
    private static String shown(MappingProblem problem) {
        String[] parts = problem.pointer().split("/", -1);
        int keep = parts.length > 3 && "lookups".equals(parts[1]) ? 3 : parts.length;
        StringBuilder place = new StringBuilder();
        for (int i = 1; i < keep; i++) {
            place.append('/').append(URLEncoder.encode(parts[i], StandardCharsets.UTF_8));
        }
        return place.toString();
    }
}
