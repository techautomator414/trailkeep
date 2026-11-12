// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.ImportCaseType;
import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagingStore;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code dry-run} (WP-26 part q, design §3 step 3): checks every staged case against Trailkeep's rules and its teams
 * and case types as they are now ({@link CaseRules}), writes each problem as a {@code DRY_RUN} issue (a code, a
 * severity and the keyed hash of the case's key, never a value), and records the attachment scanning mode in force on
 * the run ({@code app.attachments.scanning}, read as the attachment intake reads it), which the sign-off approves and
 * the load must still find (R1). It writes nothing to the domain. A dry run may be repeated until the sign-off: each
 * replaces the last one's issues. The mapping file must be the one {@code validate-mapping} accepted.
 */
@Component
@Profile("importer")
class DryRunCommand {

    /** Staged cases read per page, each page's issues written in one transaction. */
    private static final int PAGE = 500;

    /** The property the attachment intake reads its scanning mode from (ADR-0013). */
    private static final String SCANNING = "app.attachments.scanning";

    /**
     * What a dry run found.
     *
     * @param cases how many staged cases it checked
     * @param issues how many issues it recorded
     * @param fatal how many of them are FATAL, any of which stops the load
     */
    record Outcome(long cases, long issues, long fatal) {}

    private final StagingStore staging;
    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;
    private final TransactionTemplate transactions;
    private final CaseImportService caseImports;
    private final UserDirectoryService directory;
    private final ImportProperties properties;
    private final Environment environment;
    private final AppProperties app;
    private final InstantSource clock;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    DryRunCommand(
            StagingStore staging,
            ImportRunRepository runs,
            ImportIssueRepository issues,
            TransactionTemplate transactions,
            CaseImportService caseImports,
            UserDirectoryService directory,
            ImportProperties properties,
            Environment environment,
            AppProperties app,
            InstantSource clock) {
        this.staging = staging;
        this.runs = runs;
        this.issues = issues;
        this.transactions = transactions;
        this.caseImports = caseImports;
        this.directory = directory;
        this.properties = properties;
        this.environment = environment;
        this.app = app;
        this.clock = clock;
    }

    /**
     * Dry-runs an extracted run.
     *
     * @param runId the run
     * @param file the mapping file, unchanged since {@code validate-mapping}
     * @return what it found
     * @throws IllegalStateException if the run is not extracted, is signed off or purged, or the file changed
     */
    Outcome run(long runId, Path file) {
        byte[] content = ValidateMappingCommand.read(file);
        ImportRun found = load(runId);
        if (!found.awaitsDryRun()) {
            throw new IllegalStateException(
                    "Import run " + runId + " is " + found.getStatus() + ", not extracted and awaiting its sign-off");
        }
        if (!found.hasMappingHash(ValidateMappingCommand.sha256(content))) {
            throw new IllegalStateException("The mapping file changed after validate-mapping; validate it again");
        }
        Instant now = clock.instant();
        CaseRules rules = new CaseRules(
                yaml.readTree(content),
                directory.allTeams().stream().collect(Collectors.toMap(TeamSummary::name, TeamSummary::active)),
                caseImports.caseTypes().stream().collect(Collectors.toMap(ImportCaseType::name, Function.identity())),
                now,
                app.timezone());
        transactions.executeWithoutResult(status -> issues.deleteByRunIdAndStage(runId, ImportIssue.Stage.DRY_RUN));
        Outcome cases = checkCases(runId, rules, properties.hashKeySpec(), now);
        ImportRun.ScanMode mode =
                Binder.get(environment).bind(SCANNING, ImportRun.ScanMode.class).orElse(ImportRun.ScanMode.CLAMD);
        transactions.executeWithoutResult(status -> {
            ImportRun run = load(runId);
            run.dryRunDone(mode, now);
            runs.save(run);
        });
        return cases;
    }

    /**
     * Every staged case through the rules, a page at a time; then the share of cases with an ERROR against {@code
     * limits.maxErrorRate}: above it, a FATAL {@code ERROR_RATE_EXCEEDED} carrying the number of such cases.
     */
    private Outcome checkCases(long runId, CaseRules rules, SecretKey hashKey, Instant now) {
        long cases = 0;
        long failed = 0;
        long recorded = 0;
        long fatal = 0;
        @Nullable String after = null;
        for (List<StagedCase> page = staging.cases(runId, null, PAGE);
                !page.isEmpty();
                page = staging.cases(runId, after, PAGE)) {
            List<ImportIssue> found = new ArrayList<>();
            for (StagedCase row : page) {
                List<Issue> problems = rules.check(row);
                String keyHash = KeyedHash.hex(hashKey, row.getKey().legacyKey());
                problems.forEach(
                        problem -> found.add(issue(runId, keyHash, problem.severity(), problem.code(), null, now)));
                failed += problems.stream().anyMatch(problem -> problem.severity() == Severity.ERROR) ? 1 : 0;
                fatal += problems.stream()
                        .filter(problem -> problem.severity() == Severity.FATAL)
                        .count();
            }
            transactions.executeWithoutResult(status -> found.forEach(issues::save));
            cases += page.size();
            recorded += found.size();
            after = page.getLast().getKey().legacyKey();
        }
        if (failed > rules.maxErrorRate() * cases) {
            ImportIssue rate = issue(runId, null, Severity.FATAL, "ERROR_RATE_EXCEEDED", failed, now);
            transactions.executeWithoutResult(status -> issues.save(rate));
            recorded++;
            fatal++;
        }
        return new Outcome(cases, recorded, fatal);
    }

    private static ImportIssue issue(
            long runId, @Nullable String keyHash, Severity severity, String code, @Nullable Long detail, Instant now) {
        return new ImportIssue(
                runId, ImportIssue.Stage.DRY_RUN, ImportIssue.Subject.CASE, keyHash, severity, code, detail, now);
    }

    private ImportRun load(long runId) {
        return runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
    }
}
