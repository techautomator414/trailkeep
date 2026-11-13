// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.Existing.State;
import io.trailkeep.casefile.CaseImportService.ImportCaseType;
import io.trailkeep.importer.ChildRules.Finding;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedEvent;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagingStore;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import java.nio.file.Path;
import java.time.Instant;
import java.time.InstantSource;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code dry-run} (WP-26 part q, design §3 step 3): checks every staged case against Trailkeep's rules and its teams
 * and case types as they are now ({@link CaseRules}), and every staged comment, attachment and history event against
 * its case and its own rules ({@link ChildRules}), matches the staged people to Trailkeep users and checks every team
 * with open cases has an eligible default owner ({@link PeopleMatcher}, 26r2), writes each problem as a {@code DRY_RUN}
 * issue (a code, a severity and the keyed hash of the row's key, never a value), and records the attachment scanning
 * mode in force on the run ({@code app.attachments.scanning}, read as the attachment intake reads it), which the
 * sign-off approves and the load must still find (R1). Against a source an earlier load wrote rows of, rows the import
 * ledger holds are checked only for what changed in the source, and the ledger's rows this run did not stage are
 * reported ({@link LedgerDelta}, 26y1). It writes nothing to the domain. A dry run may be repeated until the sign-off:
 * each replaces the last one's issues. The mapping file and its lookup files must be the ones {@code validate-mapping}
 * accepted ({@link LookupFiles#hash}).
 */
@Component
@Profile("importer")
class DryRunCommand {

    /** Staged cases read per page, each page's issues written in one transaction. */
    private static final int PAGE = 500;

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
    private final ImportRunSummaryRepository figures;
    private final ImportLedgerRepository ledger;
    private final TransactionTemplate transactions;
    private final CaseImportService caseImports;
    private final UserDirectoryService directory;
    private final ImportPeopleService people;
    private final ImportProperties properties;
    private final Environment environment;
    private final AppProperties app;
    private final InstantSource clock;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    DryRunCommand(
            StagingStore staging,
            ImportRunRepository runs,
            ImportIssueRepository issues,
            ImportRunSummaryRepository figures,
            ImportLedgerRepository ledger,
            TransactionTemplate transactions,
            CaseImportService caseImports,
            UserDirectoryService directory,
            ImportPeopleService people,
            ImportProperties properties,
            Environment environment,
            AppProperties app,
            InstantSource clock) {
        this.staging = staging;
        this.runs = runs;
        this.issues = issues;
        this.figures = figures;
        this.ledger = ledger;
        this.transactions = transactions;
        this.caseImports = caseImports;
        this.directory = directory;
        this.people = people;
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
     * @throws IllegalStateException if the run is not extracted, is signed off or purged, or a file changed, or in
     *     {@code clamd} mode clamd's declared stream limit is unset or below the importer's cap
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
        String hash = lookups.hash(content);
        if (!lookups.problems().isEmpty() || !found.hasMappingHash(hash)) {
            throw new IllegalStateException(
                    "The mapping file or a lookup file changed after validate-mapping; validate it again");
        }
        ImportRun.ScanMode mode = ScanModeInForce.read(environment);
        ScanModeInForce.checkStreamMax(mode, properties.attachments());
        Instant now = clock.instant();
        List<TeamSummary> teams = directory.allTeams();
        CaseRules rules = new CaseRules(
                mapping,
                teams.stream().collect(Collectors.toMap(TeamSummary::name, TeamSummary::active)),
                caseImports.caseTypes().stream().collect(Collectors.toMap(ImportCaseType::name, Function.identity())),
                now,
                app.timezone());
        PeopleMatcher matcher = new PeopleMatcher(people, caseImports, mapping, lookups.defaultOwners());
        transactions.executeWithoutResult(status -> {
            issues.deleteByRunIdAndStage(runId, ImportIssue.Stage.DRY_RUN);
            figures.deleteByRunId(runId);
            ImportRun run = load(runId);
            run.dryRunStarting(now);
            runs.save(run);
        });
        ChildRules children =
                new ChildRules(mapping, properties.attachments().maxBytes().toBytes(), now);
        Pass pass = new Pass(
                runId,
                rules.maxErrorRate(),
                children.accepted(),
                properties.hashKeySpec(),
                now,
                summary(rules, teams, mapping, matcher, now),
                LedgerDelta.of(ledger, caseImports, found.getSourceId(), hash, properties.allowMappingChange()));
        Outcome checked = checkRows(pass, rules, children, matcher).plus(deltaIssues(pass));
        checked = checked.plus(runIssues(
                runId,
                matcher.teamsWithoutDefaultOwner(
                        teams.stream().collect(Collectors.toMap(TeamSummary::id, TeamSummary::name))),
                mode == ImportRun.ScanMode.EXTERNAL ? pass.summary().loadableFiles() : 0,
                now));
        for (Object[] count : issues.countByCode(runId)) {
            pass.summary().issues((Subject) count[0], (Severity) count[1], (String) count[2], (Long) count[3]);
        }
        transactions.executeWithoutResult(status -> {
            pass.summary().figures(runId, mode).forEach(figures::save);
            ImportRun run = load(runId);
            run.dryRunDone(mode, now);
            runs.save(run);
        });
        return checked;
    }

    /**
     * Every staged person matched first, then every staged row of every other entity through its rules, a page at a
     * time, a child's case looked up among the run's staged cases; then each entity's share of rows with an ERROR, at
     * extract or here, that the mapping has not accepted as skipped, against {@code limits.maxErrorRate}: above it, a
     * FATAL {@code ERROR_RATE_EXCEEDED} for that entity carrying the number of such rows.
     */
    private Outcome checkRows(Pass pass, CaseRules cases, ChildRules children, PeopleMatcher people) {
        DryRunSummary summary = pass.summary();
        return check(pass, StagedPerson.class, Subject.PERSON, row -> null, (row, staged) -> people.person(row))
                .plus(check(pass, StagedCase.class, Subject.CASE, row -> null, (row, staged) -> {
                    List<Finding> found =
                            cases.check(row).stream().map(Finding::of).toList();
                    summary.caseRow(row, found);
                    return found;
                }))
                .plus(check(pass, StagedComment.class, Subject.COMMENT, StagedComment::getCaseKey, (row, in) -> {
                    summary.comment(row);
                    return children.comment(row, in);
                }))
                .plus(check(
                        pass, StagedAttachment.class, Subject.ATTACHMENT, StagedAttachment::getCaseKey, (row, in) -> {
                            List<Finding> found = children.attachment(row, in);
                            summary.attachment(row, found);
                            return found;
                        }))
                .plus(check(pass, StagedEvent.class, Subject.EVENT, StagedEvent::getCaseKey, children::event));
    }

    /**
     * One dry run's settings: {@code limits.maxErrorRate}, the codes {@code limits.acceptSkip} keeps out of it, the
     * key that hashes each row's key into its issues, the run's time, the aggregates it counts, and what the import
     * ledger holds of its source.
     */
    private record Pass(
            long runId,
            double maxErrorRate,
            Set<String> accepted,
            SecretKey hashKey,
            Instant now,
            DryRunSummary summary,
            LedgerDelta delta) {}

    /**
     * The delta's issues about no staged row: the finding about the mapping the ledger's rows were loaded under, and a
     * {@code MISSING_IN_SOURCE} WARNING for each ledger row of the source this run did not stage, a page at a time.
     */
    private Outcome deltaIssues(Pass pass) {
        long recorded = 0;
        Finding mapping = pass.delta().mapping();
        if (mapping != null) {
            transactions.executeWithoutResult(
                    status -> issues.save(issue(pass.runId(), Subject.MAPPING, null, mapping, pass.now())));
            recorded++;
        }
        for (Subject subject : LedgerDelta.ENTITIES) {
            recorded += pass.delta().missing(subject, staging, pass.runId(), absent -> keyed(pass, subject, absent));
        }
        boolean fatal = mapping != null && mapping.severity() == Severity.FATAL;
        return new Outcome(0, recorded, fatal ? 1 : 0);
    }

    /** Records each row's finding under its key's keyed hash, in one transaction. */
    private void keyed(Pass pass, Subject subject, Map<String, Finding> found) {
        transactions.executeWithoutResult(status -> found.forEach((key, finding) ->
                issues.save(issue(pass.runId(), subject, KeyedHash.hex(pass.hashKey(), key), finding, pass.now()))));
    }

    /**
     * The run's issues about no single row: a FATAL {@code DEFAULT_OWNER_MISSING} per team with open cases and no
     * eligible default owner, carrying the team's Trailkeep id; in {@code external} scanning mode one {@code
     * NOT_SCANNED} WARNING carrying how many files the load will store unscanned.
     */
    private Outcome runIssues(long runId, List<Long> teamsWithoutOwner, long unscanned, Instant now) {
        List<Finding> found = new ArrayList<>();
        teamsWithoutOwner.forEach(team -> found.add(new Finding("DEFAULT_OWNER_MISSING", Severity.FATAL, team)));
        transactions.executeWithoutResult(status -> {
            found.forEach(finding -> issues.save(issue(runId, Subject.CASE, null, finding, now)));
            if (unscanned > 0) {
                Finding warning = new Finding("NOT_SCANNED", Severity.WARNING, unscanned);
                issues.save(issue(runId, Subject.ATTACHMENT, null, warning, now));
            }
        });
        return new Outcome(0, found.size() + (unscanned > 0 ? 1 : 0), found.size());
    }

    private <T extends StagedRow> Outcome check(
            Pass pass,
            Class<T> type,
            Subject subject,
            Function<T, @Nullable String> parent,
            BiFunction<T, Boolean, List<Finding>> rules) {
        long runId = pass.runId();
        Instant now = pass.now();
        long rows = 0;
        long held = 0;
        long recorded = 0;
        long fatal = 0;
        @Nullable String after = null;
        for (List<T> page = staging.page(type, runId, null, PAGE);
                !page.isEmpty();
                page = staging.page(type, runId, after, PAGE)) {
            LedgerDelta.Parents parents = pass.delta()
                    .parents(
                            staging,
                            runId,
                            page.stream()
                                    .map(parent)
                                    .filter(Objects::nonNull)
                                    .distinct()
                                    .toList());
            Map<String, ImportLedgerEntry> loaded = pass.delta()
                    .held(
                            subject,
                            page.stream().map(row -> row.getKey().legacyKey()).toList());
            Map<Long, State> closed = pass.delta().closed(subject, loaded.values());
            List<ImportIssue> found = new ArrayList<>();
            for (T row : page) {
                String keyHash = KeyedHash.hex(pass.hashKey(), row.getKey().legacyKey());
                String caseKey = parent.apply(row);
                ImportLedgerEntry before = loaded.get(row.getKey().legacyKey());
                Finding closedParent = parents.closed(caseKey);
                List<Finding> findings = before != null
                        ? pass.delta().changed(row, before, closed)
                        : closedParent != null ? List.of(closedParent) : rules.apply(row, parents.found(caseKey));
                for (Finding problem : findings) {
                    found.add(issue(runId, subject, keyHash, problem, now));
                    fatal += problem.severity() == Severity.FATAL ? 1 : 0;
                }
            }
            transactions.executeWithoutResult(status -> found.forEach(issues::save));
            rows += page.size();
            held += loaded.size();
            recorded += found.size();
            after = page.getLast().getKey().legacyKey();
        }
        pass.summary().rows(subject, rows, held);
        long failed = issues.countFailedRows(runId, subject, Severity.ERROR, pass.accepted());
        // People are never loaded, so they have no error rate: an unmatched person is a name snapshot. Rows an earlier
        // load wrote are not loaded again, so the rate is of the others.
        if (subject != Subject.PERSON && failed > pass.maxErrorRate() * (rows - held)) {
            Finding rate = new Finding("ERROR_RATE_EXCEEDED", Severity.FATAL, failed);
            transactions.executeWithoutResult(status -> issues.save(issue(runId, subject, null, rate, now)));
            recorded++;
            fatal++;
        }
        return new Outcome(rows, recorded, fatal);
    }

    /** An empty summary of this dry run, with its SLA forecast for the go-live day. */
    private DryRunSummary summary(
            CaseRules rules, List<TeamSummary> teams, JsonNode mapping, PeopleMatcher matcher, Instant now) {
        Instant goLive = goLive(now);
        return new DryRunSummary(
                rules,
                teams.stream().collect(Collectors.toMap(TeamSummary::name, TeamSummary::id)),
                mapping.at("/visibility/onUnrepresentableAcl").asString("restricted"),
                matcher,
                caseImports.slaForecast(goLive),
                LocalDate.ofInstant(goLive, app.timezone()));
    }

    /**
     * The instant the SLA forecast is for: the start of {@code import.go-live} in {@code app.timezone}, or now when
     * that is unset or already past, since no case can be forecast for a day gone by.
     */
    private Instant goLive(Instant now) {
        LocalDate day = properties.goLive();
        Instant start = day == null ? now : day.atStartOfDay(app.timezone()).toInstant();
        return start.isAfter(now) ? start : now;
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
