// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.casefile.CaseImportService.Relinked;
import io.trailkeep.importer.LoadRefusal.RefusedException;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.ImportPeopleService.SignedIn;
import java.time.InstantSource;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * {@code relink-owners} (WP-26 part z2, design §7.3): hands each open case the load gave its team's default owner
 * ({@link ImportOwnerPending}) to its legacy owner once that person has signed in. It runs for a {@code RELINK} run,
 * which a Compliance Admin requests on the Import runs screen naming the source, and covers the pending owners of every
 * load of that source. Each enabled user's email address and external id are hashed with {@code import.hash-key} as the
 * load hashed the legacy owner's, and a pending case matches a user by external id, else by an address only one user
 * has; an address several users share is {@code AMBIGUOUS}, nobody {@code UNMATCHED}, and both are left alone. Every
 * case goes through {@link CaseImportService#relinkOwner}, one transaction each, which reassigns a matched case only
 * while it is open, still the default owner's and the person may own the team's cases, audited. The pending row goes
 * with the case's transaction once the case is relinked, or can never be (closed, archived, purged or reassigned).
 *
 * <p>It is a dry run unless {@code import.apply} is true: the same transactions run and are rolled back, so the counts
 * forecast exactly what applying does. Either way the counts per outcome replace the run's figures (metric {@value
 * #METRIC}); a dry run leaves the run {@code DRY_RUN}, applying leaves it {@code LOADED} with the cases relinked as its
 * loaded count. A run that is not a {@code RELINK} run, or was applied already, is refused before anything is read.
 */
@Component
@Profile("importer")
class RelinkOwnersCommand {

    /** The figure that holds the counts, broken down by outcome. */
    static final String METRIC = "RELINK";

    /**
     * What a run did; the run's figures hold the count of each outcome.
     *
     * @param pending the pending cases it went through
     * @param relinked those it relinked, or in a dry run would relink
     */
    record Outcome(long pending, long relinked) {}

    /** Pending rows read per page. */
    private static final int PAGE = 500;

    /** The outcomes after which a case can never be relinked, or needs it no more. */
    private static final Set<Relinked> DONE = EnumSet.of(Relinked.RELINKED, Relinked.CLOSED, Relinked.OWNER_CHANGED);

    private final ImportRunRepository runs;
    private final ImportOwnerPendingRepository pending;
    private final ImportRunSummaryRepository summaries;
    private final CaseImportService cases;
    private final ImportPeopleService people;
    private final TransactionTemplate transactions;
    private final ImportProperties properties;
    private final InstantSource clock;

    RelinkOwnersCommand(
            ImportRunRepository runs,
            ImportOwnerPendingRepository pending,
            ImportRunSummaryRepository summaries,
            CaseImportService cases,
            ImportPeopleService people,
            TransactionTemplate transactions,
            ImportProperties properties,
            InstantSource clock) {
        this.runs = runs;
        this.pending = pending;
        this.summaries = summaries;
        this.cases = cases;
        this.people = people;
        this.transactions = transactions;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Relinks, or forecasts relinking, the pending owners of a {@code RELINK} run's source.
     *
     * @param runId the run
     * @param apply whether to change owners; otherwise a dry run
     * @return how many pending cases it went through, and how many it relinked or would relink
     * @throws RefusedException {@code NOT_LOADABLE} for a run that is not a {@code RELINK} run, or was applied
     */
    Outcome run(long runId, boolean apply) {
        ImportRun run = runs.findById(runId).orElseThrow(() -> new IllegalStateException("No import run " + runId));
        if (run.getKind() != ImportRun.Kind.RELINK
                || !EnumSet.of(ImportRun.Status.REQUESTED, ImportRun.Status.DRY_RUN)
                        .contains(run.getStatus())) {
            throw new RefusedException(runId, LoadRefusal.NOT_LOADABLE);
        }
        Map<String, Set<Long>> users = users(properties.hashKeySpec());
        SortedMap<String, Long> counts = new TreeMap<>();
        long after = Long.MIN_VALUE;
        for (List<ImportOwnerPending> page = pending.page(run.getSourceId(), after, Limit.of(PAGE));
                !page.isEmpty();
                page = pending.page(run.getSourceId(), after, Limit.of(PAGE))) {
            for (ImportOwnerPending row : page) {
                counts.merge(relink(runId, row, users, apply), 1L, Long::sum);
            }
            after = page.getLast().getId();
        }
        int relinked = apply ? Math.toIntExact(counts.getOrDefault(Relinked.RELINKED.name(), 0L)) : 0;
        transactions.executeWithoutResult(status -> {
            ImportRun current = runs.findById(runId).orElseThrow();
            summaries.deleteByRunId(runId);
            counts.forEach((outcome, count) -> summaries.save(new ImportRunSummary(runId, METRIC, outcome, count)));
            current.loadMoved(apply ? ImportRun.Status.LOADED : ImportRun.Status.DRY_RUN, relinked, clock.instant());
            runs.save(current);
        });
        return new Outcome(
                counts.values().stream().mapToLong(Long::longValue).sum(),
                counts.getOrDefault(Relinked.RELINKED.name(), 0L));
    }

    /** Every enabled user by the keyed hash of their external id and of their email address, which some may share. */
    private Map<String, Set<Long>> users(SecretKey key) {
        Map<String, Set<Long>> users = new HashMap<>();
        for (SignedIn user : people.signedIn()) {
            users.computeIfAbsent(ImportOwnerPending.externalIdHmac(key, user.externalId()), hash -> new TreeSet<>())
                    .add(user.userId());
            if (!user.email().isBlank()) {
                users.computeIfAbsent(ImportOwnerPending.emailHmac(key, user.email()), hash -> new TreeSet<>())
                        .add(user.userId());
            }
        }
        return users;
    }

    /** One pending case in its own transaction, rolled back in a dry run; its outcome's code. */
    private String relink(long runId, ImportOwnerPending row, Map<String, Set<Long>> users, boolean apply) {
        String externalId = row.getExternalIdHmac();
        String email = row.getEmailHmac();
        Set<Long> found = externalId == null ? Set.of() : users.getOrDefault(externalId, Set.of());
        if (found.isEmpty() && email != null) {
            found = users.getOrDefault(email, Set.of());
        }
        @Nullable Long owner = found.size() == 1 ? found.iterator().next() : null;
        String unmatched = found.isEmpty() ? "UNMATCHED" : "AMBIGUOUS";
        return Objects.requireNonNull(transactions.execute(status -> {
            Relinked outcome = cases.relinkOwner(runId, row.getCaseId(), row.getDefaultOwnerId(), owner);
            if (!apply) {
                status.setRollbackOnly();
            } else if (DONE.contains(outcome)) {
                pending.remove(row.getId());
            }
            return outcome == Relinked.WAITING ? unmatched : outcome.name();
        }));
    }
}
