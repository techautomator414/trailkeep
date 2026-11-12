// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.ImportCaseType;
import io.trailkeep.importer.ChildRules.Finding;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedEvent;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagingStore;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code dry-run} (WP-26 part q, design §3 step 3): checks every staged case against Trailkeep's rules and its teams
 * and case types as they are now ({@link CaseRules}), and every staged comment, attachment and history event against
 * its case and its own rules ({@link ChildRules}), writes each problem as a {@code DRY_RUN} issue (a code, a severity
 * and the keyed hash of the row's key, never a value), and records the attachment scanning mode in force on
 * the run ({@code app.attachments.scanning}, read as the attachment intake reads it), which the sign-off approves and
 * the load must still find (R1). It writes nothing to the domain. A dry run may be repeated until the sign-off: each
 * replaces the last one's issues. The mapping file and its lookup files must be the ones {@code validate-mapping}
 * accepted ({@link LookupFiles#hash}).
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
     * @param rows how many staged rows it checked
     * @param issues how many issues it recorded
     * @param fatal how many of them are FATAL, any of which stops the load
     */
    record Outcome(long rows, long issues, long fatal) {

        Outcome plus(Outcome other) {
            return new Outcome(rows + other.rows, issues + other.issues, fatal + other.fatal);
        }
    }

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
     * @param file the mapping file, unchanged since {@code validate-mapping}, as are its lookup files
     * @return what it found
     * @throws IllegalStateException if the run is not extracted, is signed off or purged, or a file changed
     */
    Outcome run(long runId, Path file) {
        byte[] content = ValidateMappingCommand.read(file);
        ImportRun found = load(runId);
        if (!found.awaitsDryRun()) {
            throw new IllegalStateException(
                    "Import run " + runId + " is " + found.getStatus() + ", not extracted and awaiting its sign-off");
        }
        JsonNode mapping = yaml.readTree(content);
        LookupFiles lookups = LookupFiles.read(file, mapping);
        if (!lookups.problems().isEmpty() || !found.hasMappingHash(lookups.hash(content))) {
            throw new IllegalStateException(
                    "The mapping file or a lookup file changed after validate-mapping; validate it again");
        }
        Instant now = clock.instant();
        CaseRules rules = new CaseRules(
                mapping,
                directory.allTeams().stream().collect(Collectors.toMap(TeamSummary::name, TeamSummary::active)),
                caseImports.caseTypes().stream().collect(Collectors.toMap(ImportCaseType::name, Function.identity())),
                now,
                app.timezone());
        transactions.executeWithoutResult(status -> issues.deleteByRunIdAndStage(runId, ImportIssue.Stage.DRY_RUN));
        ChildRules children =
                new ChildRules(mapping, properties.attachments().maxBytes().toBytes(), now);
        Outcome checked = checkRows(runId, rules, children, properties.hashKeySpec(), now);
        ImportRun.ScanMode mode =
                Binder.get(environment).bind(SCANNING, ImportRun.ScanMode.class).orElse(ImportRun.ScanMode.CLAMD);
        transactions.executeWithoutResult(status -> {
            ImportRun run = load(runId);
            run.dryRunDone(mode, now);
            runs.save(run);
        });
        return checked;
    }

    /**
     * Every staged row of every entity through its rules, a page at a time, a child's case looked up among the run's
     * staged cases; then each entity's share of rows with an ERROR, at extract or here, that the mapping has not
     * accepted as skipped, against {@code limits.maxErrorRate}: above it, a FATAL {@code ERROR_RATE_EXCEEDED} for that
     * entity carrying the number of such rows.
     */
    private Outcome checkRows(long runId, CaseRules cases, ChildRules children, SecretKey hashKey, Instant now) {
        Pass pass = new Pass(runId, cases.maxErrorRate(), children.accepted(), hashKey, now);
        return check(
                        pass,
                        StagedCase.class,
                        Subject.CASE,
                        row -> null,
                        (row, staged) ->
                                cases.check(row).stream().map(Finding::of).toList())
                .plus(check(pass, StagedComment.class, Subject.COMMENT, StagedComment::getCaseKey, children::comment))
                .plus(check(
                        pass,
                        StagedAttachment.class,
                        Subject.ATTACHMENT,
                        StagedAttachment::getCaseKey,
                        children::attachment))
                .plus(check(pass, StagedEvent.class, Subject.EVENT, StagedEvent::getCaseKey, children::event));
    }

    /**
     * One dry run's settings: {@code limits.maxErrorRate}, the codes {@code limits.acceptSkip} keeps out of it, the
     * key that hashes each row's key into its issues, and the run's time.
     */
    private record Pass(long runId, double maxErrorRate, Set<String> accepted, SecretKey hashKey, Instant now) {}

    private <T extends StagedRow> Outcome check(
            Pass pass,
            Class<T> type,
            Subject subject,
            Function<T, @Nullable String> parent,
            BiFunction<T, Boolean, List<Finding>> rules) {
        long runId = pass.runId();
        Instant now = pass.now();
        long rows = 0;
        long recorded = 0;
        long fatal = 0;
        @Nullable String after = null;
        for (List<T> page = staging.page(type, runId, null, PAGE);
                !page.isEmpty();
                page = staging.page(type, runId, after, PAGE)) {
            Set<String> parents = staging.stagedKeys(
                    StagedEntity.CASE,
                    runId,
                    page.stream()
                            .map(parent)
                            .filter(Objects::nonNull)
                            .distinct()
                            .toList());
            List<ImportIssue> found = new ArrayList<>();
            for (T row : page) {
                String keyHash = KeyedHash.hex(pass.hashKey(), row.getKey().legacyKey());
                String caseKey = parent.apply(row);
                for (Finding problem : rules.apply(row, caseKey == null || parents.contains(caseKey))) {
                    found.add(issue(runId, subject, keyHash, problem, now));
                    fatal += problem.severity() == Severity.FATAL ? 1 : 0;
                }
            }
            transactions.executeWithoutResult(status -> found.forEach(issues::save));
            rows += page.size();
            recorded += found.size();
            after = page.getLast().getKey().legacyKey();
        }
        long failed = issues.countFailedRows(runId, subject, Severity.ERROR, pass.accepted());
        if (failed > pass.maxErrorRate() * rows) {
            Finding rate = new Finding("ERROR_RATE_EXCEEDED", Severity.FATAL, failed);
            transactions.executeWithoutResult(status -> issues.save(issue(runId, subject, null, rate, now)));
            recorded++;
            fatal++;
        }
        return new Outcome(rows, recorded, fatal);
    }

    private static ImportIssue issue(
            long runId, Subject subject, @Nullable String keyHash, Finding finding, Instant now) {
        return new ImportIssue(
                runId,
                ImportIssue.Stage.DRY_RUN,
                subject,
                keyHash,
                finding.severity(),
                finding.code(),
                finding.detail(),
                now);
    }

    private ImportRun load(long runId) {
        return runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
    }
}
