// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.ImportCaseType;
import io.trailkeep.casefile.CaseImportService.ImportedCase;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.closure.ClosureImportService.ImportedClosure;
import io.trailkeep.document.AttachmentImportService;
import io.trailkeep.document.AttachmentRejectedException;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.ImportLedgerEntry.Loaded;
import io.trailkeep.importer.LoadRefusal.RefusedException;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagingStore;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.ImportPeopleDirectory;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
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
 * {@code load} (WP-26 parts t, u, w and z1, design §3 step 5, §8.2): writes the signed-off run's staged cases through
 * the import APIs, one case per transaction with its closure, comments ({@link CommentLoad}, {@link CaseComments}),
 * attachments ({@link AttachmentLoad}, scanned first), legacy history ({@link EventLoad}), pending owner ({@link
 * ImportOwnerPending}), ledger rows and the run's count, so a case is fully in or absent. It refuses to start ({@link
 * LoadRefusal}) unless the run is signed off, has no FATAL issue, is loaded with the mapping and lookup files it was
 * dry-run with, and the scanning mode in force is the one its dry run recorded ({@link ScanModeInForce}, R1). It writes
 * only cases the ledger does not hold for the source, so a crashed load resumes at the next case, then the new children
 * of cases it holds ({@link ChildOnlyLoad}), and skips every row with an ERROR from the extract or dry run. Owners are
 * chosen as the dry run forecast them ({@link PeopleMatcher}); each case is checked again against the rules and
 * reference data of the load's moment ({@link CaseRules}). A case they refuse, whose team has no eligible owner now,
 * whose attachment changed or event fails its rules now, or that the import APIs refuse as invalid, is written nowhere
 * but as a {@code LOAD} issue (an ERROR with its code and the case's keyed hash), and the load goes on; the next load
 * tries it again. Anything else stops the load, closed: the run is {@code FAILED}, and the next load resumes at that
 * case. The run lock ({@link LoadRunLock}) keeps one load per run, and one loading run per source, on any number of
 * nodes; a load that died is taken over once it has not moved its run for {@link #LEASE}.
 */
@Component
@Profile("importer")
class LoadCommand {

    /** How long a load may hold its run without moving it on before another load may take the run over. */
    static final Duration LEASE = Duration.ofMinutes(10);

    /** Staged rows read per page. */
    private static final int PAGE = 500;

    /** The {@code LOAD} issue code of a case the import APIs refuse as invalid. */
    static final String CASE_REFUSED = "CASE_REFUSED";

    /**
     * What a load did: the cases it wrote, left (in the ledger, or with an ERROR) and refused now as LOAD issues, and
     * the cases loaded before that it wrote new children onto ({@link ChildOnlyLoad}).
     */
    record Outcome(long loaded, long skipped, long refused, long extended) {}

    /** One load's run, its hold on it, settings and reference data. */
    private record Pass(
            Loaded run,
            LoadRunLock lock,
            CaseImportService.ImportRun audited,
            CaseComments.Author importer,
            SecretKey hashKey,
            Instant now,
            CaseRules rules,
            Map<String, TeamSummary> teams,
            PeopleMatcher people,
            ChildLoads children) {}

    /** How far a load got. */
    private static final class Progress {
        private long loaded;
        private long skipped;
        private long refused;
        private long extended;
    }

    private final StagingStore staging;
    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;
    private final ImportLedgerRepository ledger;
    private final ImportOwnerPendingRepository pendingOwners;
    private final TransactionTemplate transactions;
    private final CaseImportService caseImports;
    private final ClosureImportService closures;
    private final AttachmentImportService attachmentImports;
    private final ImportedHistoryService history;
    private final UserDirectoryService directory;
    private final ImportPeopleService people;
    private final ImportPeopleDirectory peopleDirectory;
    private final ImportProperties properties;
    private final Environment environment;
    private final AppProperties app;
    private final InstantSource clock;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    LoadCommand(
            StagingStore staging,
            ImportRunRepository runs,
            ImportIssueRepository issues,
            ImportLedgerRepository ledger,
            ImportOwnerPendingRepository pendingOwners,
            TransactionTemplate transactions,
            CaseImportService caseImports,
            ClosureImportService closures,
            AttachmentImportService attachmentImports,
            ImportedHistoryService history,
            UserDirectoryService directory,
            ImportPeopleService people,
            ImportPeopleDirectory peopleDirectory,
            ImportProperties properties,
            Environment environment,
            AppProperties app,
            InstantSource clock) {
        this.staging = staging;
        this.runs = runs;
        this.issues = issues;
        this.ledger = ledger;
        this.pendingOwners = pendingOwners;
        this.transactions = transactions;
        this.caseImports = caseImports;
        this.closures = closures;
        this.attachmentImports = attachmentImports;
        this.history = history;
        this.directory = directory;
        this.people = people;
        this.peopleDirectory = peopleDirectory;
        this.properties = properties;
        this.environment = environment;
        this.app = app;
        this.clock = clock;
    }

    /**
     * Loads a signed-off run, or resumes its load, with the mapping file it was validated with ({@code file}).
     *
     * @throws RefusedException if the run may not be loaded now; {@link IllegalStateException} in {@code clamd} mode
     *     if clamd's declared stream limit is unset or below the importer's cap; both before anything is written
     */
    Outcome run(long runId, Path file) {
        ImportRun found = load(runId);
        Long approver = found.getApprovedBy();
        if (approver == null) {
            throw new RefusedException(runId, LoadRefusal.NOT_SIGNED_OFF);
        }
        if (!found.awaitsLoad()) {
            throw new RefusedException(runId, LoadRefusal.NOT_LOADABLE);
        }
        if (issues.countByRunIdAndSeverity(runId, Severity.FATAL) > 0) {
            throw new RefusedException(runId, LoadRefusal.FATAL_ISSUES);
        }
        byte[] content = ValidateMappingCommand.read(file);
        JsonNode mapping = yaml.readTree(content);
        LookupFiles lookups = LookupFiles.read(file, mapping);
        String hash = lookups.hash(content);
        if (!lookups.problems().isEmpty() || !found.hasMappingHash(hash)) {
            throw new RefusedException(runId, LoadRefusal.MAPPING_CHANGED);
        }
        ScanModeInForce.checkLoad(environment, found, properties.attachments());
        Pass pass = pass(found, approver, hash, mapping, lookups);
        Progress progress = new Progress();
        // The last attempt's LOAD issues go with the claim, so the cases they name are tried again.
        pass.lock()
                .claim(
                        found.getSourceId(),
                        pass.now(),
                        () -> issues.deleteByRunIdAndStageAndSubject(runId, ImportIssue.Stage.LOAD, Subject.CASE));
        try {
            pages(StagedPerson.class, runId, page -> page.forEach(pass.people()::person));
            pages(StagedCase.class, runId, page -> loadPage(pass, progress, page));
            ChildOnlyLoad.Extended extended =
                    new ChildOnlyLoad(pass.children()).load(pass.lock(), pass.people(), pass.importer());
            progress.extended = extended.cases();
            progress.refused += extended.refused();
            pass.lock().end(false);
        } catch (RuntimeException e) {
            try {
                pass.lock().end(true);
            } catch (RuntimeException notMarked) {
                e.addSuppressed(notMarked);
            }
            throw new IllegalStateException("Import run " + runId + ": the load stopped; the next load resumes it", e);
        }
        return new Outcome(progress.loaded, progress.skipped, progress.refused, progress.extended);
    }

    /** This load's run, hold, settings and reference data, read once at its start. */
    private Pass pass(ImportRun found, long approver, String hash, JsonNode mapping, LookupFiles lookups) {
        Instant now = clock.instant();
        long maxBytes = properties.attachments().maxBytes().toBytes();
        Map<String, TeamSummary> teams =
                directory.allTeams().stream().collect(Collectors.toMap(TeamSummary::name, Function.identity()));
        Map<String, ImportCaseType> caseTypes =
                caseImports.caseTypes().stream().collect(Collectors.toMap(ImportCaseType::name, Function.identity()));
        CommentLoad.Context children = new CommentLoad.Context(
                staging,
                issues,
                ledger,
                caseImports,
                new Loaded(found.getId(), found.getSourceId(), hash),
                new CaseImportService.ImportRun(found.getId(), approver),
                properties.hashKeySpec(),
                now,
                new CaseComments(mapping));
        return new Pass(
                new Loaded(found.getId(), found.getSourceId(), hash),
                new LoadRunLock(runs, transactions, clock, found.getId()),
                new CaseImportService.ImportRun(found.getId(), approver),
                CaseComments.Author.importer(people.principals().importer(), directory),
                properties.hashKeySpec(),
                now,
                new CaseRules(
                        mapping,
                        teams.values().stream().collect(Collectors.toMap(TeamSummary::name, TeamSummary::active)),
                        caseTypes,
                        now,
                        app.timezone()),
                teams,
                new PeopleMatcher(peopleDirectory, caseImports, mapping, lookups.defaultOwners()),
                new ChildLoads(
                        new CommentLoad(children),
                        new AttachmentLoad(children, attachmentImports, mapping, properties.contentArea(), maxBytes),
                        new EventLoad(children, history, new ChildRules(mapping, maxBytes, now))));
    }

    /** One page of staged cases: those the ledger holds or with an ERROR are skipped, the others loaded one by one. */
    private void loadPage(Pass pass, Progress progress, List<StagedCase> page) {
        Set<String> done = Set.copyOf(ledger.loaded(
                pass.run().sourceId(),
                Subject.CASE,
                page.stream().map(row -> row.getKey().legacyKey()).toList()));
        Map<String, String> hashes = page.stream()
                .map(row -> row.getKey().legacyKey())
                .collect(Collectors.toMap(Function.identity(), key -> KeyedHash.hex(pass.hashKey(), key)));
        Set<String> failed =
                Set.copyOf(issues.keyHashesWith(pass.run().runId(), Subject.CASE, Severity.ERROR, hashes.values()));
        Map<String, ChildLoads.Staged> children = pass.children().staged(List.copyOf(hashes.keySet()));
        for (StagedCase row : page) {
            String key = row.getKey().legacyKey();
            String keyHash = Objects.requireNonNull(hashes.get(key), "hashed");
            if (done.contains(key) || failed.contains(keyHash)) {
                progress.skipped++;
                continue;
            }
            String refusal = loadCase(pass, row, Objects.requireNonNull(children.get(key), "staged"));
            if (refusal == null) {
                progress.loaded++;
                continue;
            }
            // Recorded in a transaction that moves the run on, as a loaded case's does.
            pass.lock()
                    .write(
                            0,
                            () -> issues.save(new ImportIssue(
                                    pass.run().runId(),
                                    ImportIssue.Stage.LOAD,
                                    Subject.CASE,
                                    keyHash,
                                    Severity.ERROR,
                                    refusal,
                                    null,
                                    pass.now())));
            progress.refused++;
        }
    }

    /**
     * Loads one case in its own transaction, its attachments scanned first, with the run's count, which also proves
     * this load still holds the run; {@code null} once loaded, else the code of what refused it.
     */
    private @Nullable String loadCase(Pass pass, StagedCase row, ChildLoads.Staged staged) {
        List<FieldTransformer.Issue> problems = pass.rules().check(row);
        if (!problems.isEmpty()) {
            return problems.getFirst().code();
        }
        TeamSummary team = Objects.requireNonNull(pass.teams().get(row.getTeamKey()), "checked");
        PeopleMatcher.Owner owner = pass.people()
                .owner(
                        row,
                        team.id(),
                        team.name(),
                        Objects.requireNonNull(pass.importer().id(), "set"));
        String child = pass.children().events().refusal(staged.events());
        if (owner == null || child != null) {
            return owner == null ? "DEFAULT_OWNER_MISSING" : child;
        }
        CaseComments texts = pass.children().comments().texts();
        List<CaseComments.Comment> comments = texts.of(row, staged.comments(), pass.people(), pass.importer());
        String description = texts.description(Objects.requireNonNull(row.getDescription(), "checked"));
        ImportedCase imported = pass.rules().imported(row, pass.run().sourceId(), team.id(), owner, description);
        AttachmentLoad.Scanned scanned = pass.children().attachments().scan(staged.files(), pass.lock());
        if (scanned.refusal() != null) {
            return scanned.refusal();
        }
        try {
            pass.lock().write(1, () -> {
                CaseImportService.Created created = caseImports.importCase(pass.audited(), imported);
                Instant closedAt = imported.closedAt();
                if (closedAt != null) {
                    closures.importClosure(new ImportedClosure(
                            created.caseId(),
                            Objects.requireNonNull(row.getClosureOutcome(), "checked"),
                            row.getClosureSummary(),
                            closedAt,
                            pass.run().runId(),
                            pass.audited().approverId()));
                }
                List<Long> continued =
                        pass.children().write(created, comments, scanned, staged, pass.people(), pass.importer());
                ledger.save(ledgerRow(pass, row, imported, created.caseId(), continued));
                ImportOwnerPending.of(created.caseId(), owner, pass.run().runId(), pass.hashKey(), pass.now())
                        .ifPresent(pendingOwners::save);
            });
        } catch (AttachmentRejectedException changed) {
            return AttachmentLoad.CONTENT_CHANGED;
        } catch (DomainException refused) {
            if (refused.kind() != DomainException.Kind.INVALID) {
                throw refused;
            }
            return CASE_REFUSED;
        }
        return null;
    }

    /** A loaded case's ledger row, with the hashes of what its transaction wrote, read back (design §9). */
    private ImportLedgerEntry ledgerRow(
            Pass pass, StagedCase row, ImportedCase imported, long caseId, List<Long> continued) {
        Instant closedAt = imported.closedAt();
        return ImportLedgerEntry.ofCase(
                        pass.run(),
                        row.getKey().legacyKey(),
                        row.getRowHash(),
                        caseId,
                        imported.status().name(),
                        closedAt,
                        pass.now())
                .written(
                        WrittenHash.ofCase(pass.hashKey(), caseImports, caseId, continued),
                        closedAt == null ? null : WrittenHash.ofClosure(pass.hashKey(), closures, caseId),
                        continued);
    }

    private <T extends StagedRow> void pages(Class<T> type, long runId, Consumer<List<T>> each) {
        @Nullable String after = null;
        for (List<T> page = staging.page(type, runId, null, PAGE);
                !page.isEmpty();
                page = staging.page(type, runId, after, PAGE)) {
            each.accept(page);
            after = page.getLast().getKey().legacyKey();
        }
    }

    private ImportRun load(long runId) {
        return runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
    }
}
