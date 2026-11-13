// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Creates imported cases (WP-26 part e, docs/design/importer.md §7.1, §8.1), and lists the case types with their
 * outcomes, checks owner eligibility and forecasts open cases' SLA states for the dry run (26q, 26r). Called only by
 * {@code importer} (ArchUnit), never on a request thread. It keeps the rules of {@link CaseService#create} except the
 * relaxations of §8.1 R6, each tested by name:
 *
 * <ol>
 *   <li>the case opens at the legacy {@code OPENED_AT}, not the clock ({@code importedCaseKeepsLegacyOpenedAt});
 *   <li>it may be created {@code CLOSED}, with the legacy {@code CLOSED_AT} ({@code importedCaseMayBeCreatedClosed});
 *   <li>a closed case may use an inactive case type ({@code closedImportMayUseInactiveType});
 *   <li>a closed case may be owned by the "Trailkeep import" principal ({@code closedImportMayBeOwnedByPrincipal});
 *   <li>an open case may be owned by the team's default owner, whom the importer chose in place of an unmatched legacy
 *       owner, with the legacy name kept as a snapshot ({@code defaultOwnerApplied}).
 * </ol>
 *
 * Nothing else is relaxed: every case needs an active team, an open case also an active type and an owner who may own
 * the team's cases, and the case number is a new one of the load year. The creator is the import principal; the audit
 * event has origin {@code IMPORT} and the run in its details.
 */
public interface CaseImportService {

    /**
     * One legacy case, canonicalised and validated by the importer.
     *
     * @param fields the fields a person would enter; for a closed case the type may be inactive and the owner may be
     *     the import principal
     * @param legacyRef {@code <source id>:<canonical key>}, unique across imports
     * @param legacyNumber the number the case had in the legacy tracker, or {@code null}
     * @param ownerNameSnapshot the legacy owner's name when they matched no user, or {@code null}
     * @param status {@code OPEN} or {@code CLOSED}
     * @param openedAt when the legacy case was opened
     * @param closedAt when it was closed; required for {@code CLOSED}, {@code null} for {@code OPEN}
     */
    record ImportedCase(
            CaseFields fields,
            String legacyRef,
            @Nullable String legacyNumber,
            @Nullable String ownerNameSnapshot,
            CaseStatus status,
            Instant openedAt,
            @Nullable Instant closedAt) {}

    /**
     * The import run a case belongs to, for its audit event.
     *
     * @param runId the run
     * @param approverId the Compliance Admin who signed the run off
     */
    record ImportRun(long runId, long approverId) {}

    /**
     * One legacy comment (design §7.2), its body already formatted by the importer: plain and HTML text
     * Markdown-escaped, Markdown as written. A description longer than 20 000 characters continues in the case's first
     * imported comment, written at the case's opening time by the import principal, so its overflow arrives here
     * escaped like any plain comment (§6.2).
     *
     * @param caseNumber the imported case
     * @param body the comment, 1 to 20 000 characters
     * @param authorId the matched author, or {@code null} for an unmatched one
     * @param authorName the author's name as the legacy system recorded it, or the matched person's
     * @param writtenAt when it was written in the legacy system
     */
    record ImportedComment(
            CaseNumber caseNumber, String body, @Nullable Long authorId, String authorName, Instant writtenAt) {}

    /**
     * Adds an imported comment with its legacy time and author snapshot (R6 6, {@code
     * importedCommentKeepsLegacyTimeAndAuthor}), appends it to the search text and audits it, in the caller's
     * transaction. Nobody is notified: imported history is not news.
     *
     * @param run the run
     * @param comment the comment
     * @return the comment's id
     * @throws CaseException {@code INVALID} for a blank or over-long body or a future time; {@code NOT_FOUND} for an
     *     unknown case
     */
    long importComment(ImportRun run, ImportedComment comment);

    /**
     * A case type as the dry run checks staged cases against it (WP-26 part q, design §7.1), and the load names it.
     *
     * @param id its id, which an imported case's fields carry
     * @param name its name, which the mapping's {@code caseTypeKey} names exactly
     * @param active whether an open case may use it
     * @param retentionYears how long after closing its cases are archived
     * @param outcomes the closure outcomes it allows
     */
    record ImportCaseType(long id, String name, boolean active, int retentionYears, Set<String> outcomes) {

        /**
         * Copies the outcomes, so the record holds nothing mutable.
         *
         * @param id its id
         * @param name its name
         * @param active whether an open case may use it
         * @param retentionYears how long after closing its cases are archived
         * @param outcomes the closure outcomes it allows
         */
        public ImportCaseType {
            outcomes = Set.copyOf(outcomes);
        }
    }

    /**
     * Every case type with its outcomes, read-only, for the dry run.
     *
     * @return them, by name
     */
    List<ImportCaseType> caseTypes();

    /**
     * Whether a user could own an imported open case in a team (WP-26 part r, design §7.3): enabled and allowed to own
     * the team's cases ({@code mayOwn}: Analyst or Manager), and never the legacy-approval principal. The dry run
     * asks this of every matched legacy owner and default owner, so {@link #importCase} refuses nobody it accepted.
     *
     * @param userId the user
     * @param teamId the team
     * @return true if {@link #importCase} would accept them as an open case's owner
     */
    boolean eligibleOwner(long userId, long teamId);

    /**
     * The SLA state an imported open case would have at one instant (WP-26 part r3, design §7.1): its case type's
     * policy started from its original dates by the one anchor rule (ADR-0007) and walked to that instant, exactly as
     * {@link #importCase} computes it at load.
     */
    @FunctionalInterface
    interface SlaForecast {

        /**
         * The state of one open case.
         *
         * @param caseTypeName its case type's name
         * @param identifiedOn when the matter was identified
         * @param openedAt when the legacy case was opened
         * @return the state, or {@code null} for a case type Trailkeep does not have
         */
        @Nullable
        SlaState state(String caseTypeName, LocalDate identifiedOn, Instant openedAt);
    }

    /**
     * A forecast of open cases' SLA states at one instant, every case type's policy read once, for the dry run.
     *
     * @param at the instant, the go-live day the dry run forecasts
     * @return the forecast, which reads nothing more
     */
    SlaForecast slaForecast(Instant at);

    /**
     * An imported case as created.
     *
     * @param caseId its id, which its closure and the import ledger name (WP-26 part t)
     * @param number its new Trailkeep number
     */
    record Created(long caseId, CaseNumber number) {}

    /**
     * Creates the case with its SLA row and search text, and audits it, in the caller's transaction.
     *
     * @param run the run
     * @param imported the case
     * @return its id and new Trailkeep number
     * @throws CaseException {@code INVALID} for a case the rules refuse
     */
    Created importCase(ImportRun run, ImportedCase imported);

    /**
     * Cases an earlier load created, for a delta's child-only load (WP-26 part y2, design §3.1): their ids and numbers,
     * the number being what the comment import API names a case by.
     *
     * @param caseIds case ids from the import ledger, at most a page's worth
     * @return those that exist, by id
     */
    Map<Long, Created> existing(Collection<Long> caseIds);
}
