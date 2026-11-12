// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.InstantSource;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code validate-mapping} for one run (design §3 step 1), structural checks only for now (26b). The problems replace
 * the run's earlier {@code VALIDATE} issues, each a {@code FATAL} issue about the mapping; a mapping with none moves
 * the run to {@code VALIDATED} with the file's SHA-256 as its mapping hash, which the sign-off and the load compare
 * against later (§3); a mapping with problems puts it back to {@code REQUESTED} with no hash, so an earlier pass
 * never stands beside a failed file. The file holds no legacy data, so a plain hash is enough.
 */
@Component
class ValidateMappingCommand {

    private final MappingValidator validator;
    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;
    private final TransactionTemplate transactions;
    private final InstantSource clock;

    ValidateMappingCommand(
            MappingValidator validator,
            ImportRunRepository runs,
            ImportIssueRepository issues,
            TransactionTemplate transactions,
            InstantSource clock) {
        this.validator = validator;
        this.runs = runs;
        this.issues = issues;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Validates the mapping file for a run and records the outcome on it.
     *
     * @param runId the run
     * @param mapping the mapping file
     * @return the problems found; empty when the run is now {@code VALIDATED}
     * @throws IllegalStateException if the run does not exist or is past the point of validating a mapping
     */
    List<MappingProblem> run(long runId, Path mapping) {
        byte[] content = read(mapping);
        List<MappingProblem> problems = content.length == 0 && size(mapping) > MappingValidator.MAX_BYTES
                ? List.of(new MappingProblem(MappingProblem.Code.MAPPING_TOO_LARGE, ""))
                : validator.validate(mapping, content);
        transactions.executeWithoutResult(status -> {
            ImportRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
            if (!run.awaitsMapping()) {
                throw new IllegalStateException(
                        "Import run " + runId + " is " + run.getStatus() + ", not awaiting a mapping");
            }
            issues.deleteByRunIdAndStage(runId, ImportIssue.Stage.VALIDATE);
            for (MappingProblem problem : problems) {
                issues.save(new ImportIssue(
                        runId,
                        ImportIssue.Stage.VALIDATE,
                        ImportIssue.Subject.MAPPING,
                        null,
                        ImportIssue.Severity.FATAL,
                        problem.code().name(),
                        null,
                        clock.instant()));
            }
            if (problems.isEmpty()) {
                run.mappingValidated(sha256(content), clock.instant());
            } else {
                run.mappingRejected(clock.instant());
            }
            runs.save(run);
        });
        return problems;
    }

    /** The file's bytes; none when it is too large to be configuration, so it is never read into memory. */
    private static byte[] read(Path mapping) {
        try {
            return size(mapping) > MappingValidator.MAX_BYTES ? new byte[0] : Files.readAllBytes(mapping);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the mapping file", e);
        }
    }

    private static long size(Path mapping) {
        try {
            return Files.size(mapping);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the mapping file", e);
        }
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java platform", e);
        }
    }
}
