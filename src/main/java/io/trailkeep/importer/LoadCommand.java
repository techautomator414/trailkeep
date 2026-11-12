// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseFields;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.ImportCaseType;
import io.trailkeep.casefile.CaseImportService.ImportedCase;
import io.trailkeep.casefile.CasePriority;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.closure.ClosureImportService.ImportedClosure;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.ImportIssue.Subject;
import io.trailkeep.importer.ImportLedgerEntry.Loaded;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagingStore;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.config.AppProperties;
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
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code load} (WP-26 part t, design §3 step 5, §8.2): writes the signed-off run's staged cases into Trailkeep through
 * {@code casefile}'s and {@code closure}'s import APIs, one case per transaction with its closure, its import ledger
 * row and the run's count, so a case is either fully in or absent. It refuses to start, each refusal its own code,
 * unless the run is signed off, has no FATAL issue and is loaded with the mapping file and lookup files it was
 * validated and dry-run with: the sign-off checks the first two already, and the load checks all three again. It
 * writes only cases the ledger does not hold for the run's source, so a crashed load resumes at the next case and a
 * load after {@code LOADED} writes nothing, and it skips every case with an ERROR from the extract or the dry run.
 * Owners are chosen as the dry run forecast them ({@link PeopleMatcher}). A case the import APIs refuse stops the
 * load, closed: the run is {@code FAILED}, and the next load resumes at that case.
 *
 * <p>The run lock: a load claims the run by moving it to {@code LOADING} with a conditional update on its version,
 * and every case's transaction checks that version and moves it on, so of two loads only one writes, on any number
 * of nodes. A load that died leaves {@code LOADING} behind; once it has not moved for {@link #LEASE}, the next load
 * takes it over. The load ends {@code LOADED}, or {@code FAILED} on an error.
 */
@Component
@Profile("importer")
class LoadCommand {

    /** How long a load may hold its run without moving it on before another load may take the run over. */
    static final Duration LEASE = Duration.ofMinutes(10);

    /** Staged rows read per page. */
    private static final int PAGE = 500;

    /** {@code CaseFields}' cap; a longer description continues in a comment, which the comment load brings (26u). */
    private static final int MAX_DESCRIPTION = 20_000;

    /** Why {@code load} refused to start, before it wrote anything. */
    enum Refusal {
        /** No Compliance Admin has signed the run off. */
        NOT_SIGNED_OFF,
        /** Not an import run with its staging, or past its load. */
        NOT_LOADABLE,
        /** Its dry run found a FATAL issue. */
        FATAL_ISSUES,
        /** The mapping file or a lookup file is not the one it was validated and dry-run with. */
        MAPPING_CHANGED,
        /** Another load holds it, or claimed it first. */
        RUN_LOCKED
    }

    /** {@code load} refused to start. */
    static final class RefusedException extends IllegalStateException {

        private static final long serialVersionUID = 1L;

        private final Refusal refusal;

        RefusedException(long runId, Refusal refusal) {
            super("Import run " + runId + " may not be loaded: " + refusal.name());
            this.refusal = refusal;
        }

        Refusal refusal() {
            return refusal;
        }
    }

    /**
     * What a load did.
     *
     * @param loaded the cases it wrote
     * @param skipped the cases it left: in the ledger already, or with an ERROR from the extract or the dry run
     */
    record Outcome(long loaded, long skipped) {}

    /** One load's run, settings and reference data. */
    private record Pass(
            Loaded run,
            CaseImportService.ImportRun audited,
            long importer,
            SecretKey hashKey,
            Instant now,
            CaseRules rules,
            Map<String, TeamSummary> teams,
            Map<String, ImportCaseType> caseTypes,
            PeopleMatcher people) {}

    /** How far a load got, and the run version it holds. */
    private static final class Progress {
        private int version;
        private long loaded;
        private long skipped;
    }

    private final StagingStore staging;
    private final ImportRunRepository runs;
    private final ImportIssueRepository issues;
    private final ImportLedgerRepository ledger;
    private final TransactionTemplate transactions;
    private final CaseImportService caseImports;
    private final ClosureImportService closures;
    private final UserDirectoryService directory;
    private final ImportPeopleService people;
    private final ImportProperties properties;
    private final AppProperties app;
    private final InstantSource clock;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    LoadCommand(
            StagingStore staging,
            ImportRunRepository runs,
            ImportIssueRepository issues,
            ImportLedgerRepository ledger,
            TransactionTemplate transactions,
            CaseImportService caseImports,
            ClosureImportService closures,
            UserDirectoryService directory,
            ImportPeopleService people,
            ImportProperties properties,
            AppProperties app,
            InstantSource clock) {
        this.staging = staging;
        this.runs = runs;
        this.issues = issues;
        this.ledger = ledger;
        this.transactions = transactions;
        this.caseImports = caseImports;
        this.closures = closures;
        this.directory = directory;
        this.people = people;
        this.properties = properties;
        this.app = app;
        this.clock = clock;
    }

    /**
     * Loads a signed-off run, or resumes its load.
     *
     * @param runId the run
     * @param file the mapping file, unchanged since {@code validate-mapping}, as are its lookup files
     * @return what it did
     * @throws RefusedException if the run may not be loaded now, before anything is written
     */
    Outcome run(long runId, Path file) {
        ImportRun found = load(runId);
        Long approver = found.getApprovedBy();
        if (approver == null) {
            throw new RefusedException(runId, Refusal.NOT_SIGNED_OFF);
        }
        if (!found.awaitsLoad()) {
            throw new RefusedException(runId, Refusal.NOT_LOADABLE);
        }
        if (issues.countByRunIdAndSeverity(runId, Severity.FATAL) > 0) {
            throw new RefusedException(runId, Refusal.FATAL_ISSUES);
        }
        byte[] content = ValidateMappingCommand.read(file);
        JsonNode mapping = yaml.readTree(content);
        LookupFiles lookups = LookupFiles.read(file, mapping);
        String hash = lookups.hash(content);
        if (!lookups.problems().isEmpty() || !found.hasMappingHash(hash)) {
            throw new RefusedException(runId, Refusal.MAPPING_CHANGED);
        }
        Instant now = clock.instant();
        Map<String, TeamSummary> teams =
                directory.allTeams().stream().collect(Collectors.toMap(TeamSummary::name, Function.identity()));
        Map<String, ImportCaseType> caseTypes =
                caseImports.caseTypes().stream().collect(Collectors.toMap(ImportCaseType::name, Function.identity()));
        Pass pass = new Pass(
                new Loaded(runId, found.getSourceId(), hash),
                new CaseImportService.ImportRun(runId, approver),
                people.principals().importer(),
                properties.hashKeySpec(),
                now,
                new CaseRules(
                        mapping,
                        teams.values().stream().collect(Collectors.toMap(TeamSummary::name, TeamSummary::active)),
                        caseTypes,
                        now,
                        app.timezone()),
                teams,
                caseTypes,
                new PeopleMatcher(people, caseImports, mapping, lookups.defaultOwners()));
        Progress progress = new Progress();
        progress.version = claim(runId, now);
        try {
            pages(StagedPerson.class, runId, page -> page.forEach(pass.people()::person));
            pages(StagedCase.class, runId, page -> loadPage(pass, progress, page));
            end(runId, progress, false);
        } catch (RuntimeException e) {
            try {
                end(runId, progress, true);
            } catch (RuntimeException notMarked) {
                e.addSuppressed(notMarked);
            }
            throw e;
        }
        return new Outcome(progress.loaded, progress.skipped);
    }

    /**
     * Claims the run, unless a live load holds it; a load that loses the race to claim it is refused too.
     *
     * @return the run's version once claimed
     */
    private int claim(long runId, Instant now) {
        ImportRun claimed;
        try {
            claimed = transactions.execute(status -> {
                ImportRun run = load(runId);
                if (run.loadingSince(now.minus(LEASE))) {
                    throw new RefusedException(runId, Refusal.RUN_LOCKED);
                }
                run.loadMoved(ImportRun.Status.LOADING, 0, now);
                return runs.save(run);
            });
        } catch (OptimisticLockingFailureException raced) {
            throw new RefusedException(runId, Refusal.RUN_LOCKED);
        }
        return Objects.requireNonNull(claimed, "claimed").getVersion();
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
        for (StagedCase row : page) {
            String key = row.getKey().legacyKey();
            if (done.contains(key) || failed.contains(hashes.get(key))) {
                progress.skipped++;
            } else {
                loadCase(pass, progress, row);
                progress.loaded++;
            }
        }
    }

    /**
     * Loads one case in its own transaction: the case, its closure when closed, its ledger row and the run's count,
     * which also proves this load still holds the run.
     */
    private void loadCase(Pass pass, Progress progress, StagedCase row) {
        TeamSummary team = Objects.requireNonNull(pass.teams().get(row.getTeamKey()), "dry-run checked");
        PeopleMatcher.Owner owner = pass.people().owner(row, team.id(), team.name(), pass.importer());
        String description = Objects.requireNonNull(row.getDescription(), "dry-run checked");
        if (owner == null || description.length() > MAX_DESCRIPTION) {
            // The keyed hash names the case as its issues do; a description continued in a comment loads with 26u.
            throw new IllegalStateException("Import run " + pass.run().runId() + ": case "
                    + KeyedHash.hex(pass.hashKey(), row.getKey().legacyKey())
                    + " has no eligible owner or a long description");
        }
        ImportedCase imported = imported(pass, row, team.id(), owner, description);
        transactions.executeWithoutResult(status -> {
            ImportRun run = held(pass.run().runId(), progress);
            CaseImportService.Created created = caseImports.importCase(pass.audited(), imported);
            Instant closedAt = imported.closedAt();
            if (closedAt != null) {
                closures.importClosure(new ImportedClosure(
                        created.caseId(),
                        Objects.requireNonNull(row.getClosureOutcome(), "dry-run checked"),
                        row.getClosureSummary(),
                        closedAt,
                        pass.run().runId(),
                        pass.audited().approverId()));
            }
            ledger.save(ImportLedgerEntry.ofCase(
                    pass.run(),
                    row.getKey().legacyKey(),
                    row.getRowHash(),
                    created.caseId(),
                    imported.status().name(),
                    closedAt,
                    pass.now()));
            run.loadMoved(ImportRun.Status.LOADING, 1, clock.instant());
            runs.save(run);
        });
        progress.version++;
    }

    private static ImportedCase imported(
            Pass pass, StagedCase row, long teamId, PeopleMatcher.Owner owner, String description) {
        ImportCaseType type = Objects.requireNonNull(pass.caseTypes().get(row.getCaseTypeKey()), "dry-run checked");
        CaseFields fields = new CaseFields(
                type.id(),
                Objects.requireNonNull(row.getTitle(), "dry-run checked"),
                row.getPriority() == null ? CasePriority.NORMAL : CasePriority.valueOf(row.getPriority()),
                Objects.requireNonNull(row.getIdentifiedOn(), "dry-run checked"),
                teamId,
                owner.userId(),
                row.getSubjectText(),
                description,
                Sensitivity.valueOf(pass.rules().visibility(row)));
        return new ImportedCase(
                fields,
                pass.run().sourceId() + ":" + row.getKey().legacyKey(),
                row.getLegacyNumber(),
                owner.nameSnapshot(),
                CaseStatus.valueOf(Objects.requireNonNull(row.getStatus(), "dry-run checked")),
                Objects.requireNonNull(row.getOpenedAt(), "dry-run checked"),
                row.getClosedAt());
    }

    /** The run, provided this load still holds it: its version is the one this load last wrote. */
    private ImportRun held(long runId, Progress progress) {
        ImportRun run = load(runId);
        if (run.getVersion() != progress.version) {
            throw new RefusedException(runId, Refusal.RUN_LOCKED);
        }
        return run;
    }

    /** Ends the load, {@code LOADED} or {@code FAILED}, provided it still holds the run. */
    private void end(long runId, Progress progress, boolean failed) {
        transactions.executeWithoutResult(status -> {
            ImportRun run = held(runId, progress);
            run.loadMoved(failed ? ImportRun.Status.FAILED : ImportRun.Status.LOADED, 0, clock.instant());
            runs.save(run);
        });
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
