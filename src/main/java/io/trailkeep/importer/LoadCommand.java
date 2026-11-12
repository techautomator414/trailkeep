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
import io.trailkeep.shared.DomainException;
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
 * Owners are chosen as the dry run forecast them ({@link PeopleMatcher}). Every other case is checked again against
 * the rules and the reference data of the load's moment ({@link CaseRules}): a case they refuse now, whose team has
 * no eligible owner now, or that the import APIs refuse as invalid, is written nowhere but as a {@code LOAD} issue
 * (an ERROR with its code and the case's keyed hash), and the load goes on. The next load drops those issues and
 * tries their cases again. Anything else a case's transaction cannot do stops the load, closed: the run is {@code
 * FAILED}, and the next load resumes at that case.
 *
 * <p>The run lock ({@link LoadRunLock}) keeps one load per run, and one loading run per source, on any number of
 * nodes; a load that died is taken over once it has not moved its run for {@link #LEASE}. The load ends {@code
 * LOADED}, or {@code FAILED} on an error.
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
        RUN_LOCKED,
        /** A live load holds another run of its source (design §8.2). */
        SOURCE_LOADING
    }

    /** The {@code LOAD} issue code of a case the import APIs refuse as invalid. */
    static final String CASE_REFUSED = "CASE_REFUSED";

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
     * @param refused the cases refused now, each a {@code LOAD} issue
     */
    record Outcome(long loaded, long skipped, long refused) {}

    /** One load's run, its hold on it, settings and reference data. */
    private record Pass(
            Loaded run,
            LoadRunLock lock,
            CaseImportService.ImportRun audited,
            long importer,
            SecretKey hashKey,
            Instant now,
            CaseRules rules,
            Map<String, TeamSummary> teams,
            Map<String, ImportCaseType> caseTypes,
            PeopleMatcher people) {}

    /** How far a load got. */
    private static final class Progress {
        private long loaded;
        private long skipped;
        private long refused;
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
                new LoadRunLock(runs, transactions, clock, runId),
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
        // The last attempt's LOAD issues go with the claim, so the cases they name are tried again.
        pass.lock().claim(found.getSourceId(), now, () -> issues.deleteByRunIdAndStage(runId, ImportIssue.Stage.LOAD));
        try {
            pages(StagedPerson.class, runId, page -> page.forEach(pass.people()::person));
            pages(StagedCase.class, runId, page -> loadPage(pass, progress, page));
            pass.lock().end(false);
        } catch (RuntimeException e) {
            try {
                pass.lock().end(true);
            } catch (RuntimeException notMarked) {
                e.addSuppressed(notMarked);
            }
            throw new IllegalStateException("Import run " + runId + ": the load stopped; the next load resumes it", e);
        }
        return new Outcome(progress.loaded, progress.skipped, progress.refused);
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
            String keyHash = Objects.requireNonNull(hashes.get(key), "hashed");
            if (done.contains(key) || failed.contains(keyHash)) {
                progress.skipped++;
                continue;
            }
            String refusal = loadCase(pass, row);
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
     * Loads one case in its own transaction: the case, its closure when closed, its ledger row and the run's count,
     * which also proves this load still holds the run. A case the rules or the import APIs refuse is not written.
     *
     * @return {@code null} once loaded, else the code of what refused it
     */
    private @Nullable String loadCase(Pass pass, StagedCase row) {
        List<FieldTransformer.Issue> problems = pass.rules().check(row);
        if (!problems.isEmpty()) {
            return problems.getFirst().code();
        }
        TeamSummary team = Objects.requireNonNull(pass.teams().get(row.getTeamKey()), "checked");
        PeopleMatcher.Owner owner = pass.people().owner(row, team.id(), team.name(), pass.importer());
        if (owner == null) {
            return "DEFAULT_OWNER_MISSING";
        }
        String description = Objects.requireNonNull(row.getDescription(), "checked");
        if (description.length() > MAX_DESCRIPTION) {
            // The rest continues in a comment, which the comment load brings (26u); until then the case waits.
            return "DESCRIPTION_CONTINUED";
        }
        ImportedCase imported = imported(pass, row, team.id(), owner, description);
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
                ledger.save(ImportLedgerEntry.ofCase(
                        pass.run(),
                        row.getKey().legacyKey(),
                        row.getRowHash(),
                        created.caseId(),
                        imported.status().name(),
                        closedAt,
                        pass.now()));
            });
        } catch (DomainException refused) {
            if (refused.kind() != DomainException.Kind.INVALID) {
                throw refused;
            }
            return CASE_REFUSED;
        }
        return null;
    }

    private static ImportedCase imported(
            Pass pass, StagedCase row, long teamId, PeopleMatcher.Owner owner, String description) {
        ImportCaseType type = Objects.requireNonNull(pass.caseTypes().get(row.getCaseTypeKey()), "checked");
        CaseFields fields = new CaseFields(
                type.id(),
                Objects.requireNonNull(row.getTitle(), "checked"),
                row.getPriority() == null ? CasePriority.NORMAL : CasePriority.valueOf(row.getPriority()),
                Objects.requireNonNull(row.getIdentifiedOn(), "checked"),
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
                CaseStatus.valueOf(Objects.requireNonNull(row.getStatus(), "checked")),
                Objects.requireNonNull(row.getOpenedAt(), "checked"),
                row.getClosedAt());
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
