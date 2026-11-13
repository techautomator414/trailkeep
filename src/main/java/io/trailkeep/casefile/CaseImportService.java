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
 *       owner, with the legacy name kept as a snapshot, and {@link #relinkOwner} later hands it to that legacy owner
 *       with no human actor ({@code defaultOwnerAppliedAndRelinked}).
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

    /** What {@link #relinkOwner} did with a case, and whether it can still be relinked later. */
    enum Relinked {
        /** Handed to its legacy owner, or the legacy owner is the default owner: done. */
        RELINKED,
        /** Still open and the default owner's, with nobody named to hand it to: it still waits. */
        WAITING,
        /** Its closure is pending: left alone, and it waits, since a rejected closure reopens it. */
        CLOSURE_PENDING,
        /** Closed, archived or purged: it can never be relinked. */
        CLOSED,
        /** Someone reassigned it since the load: they win, and it can never be relinked. */
        OWNER_CHANGED,
        /** The legacy owner may not own the team's cases, or is disabled: left alone, and it waits. */
        INELIGIBLE
    }

    /**
     * Hands an open case from its team's default owner to its legacy owner, matched since the load (WP-26 part z2,
     * design §7.3), in the caller's transaction and with no human actor: the import principal changes the owner, and
     * the audit event {@code CASE_OWNER_RELINKED} (origin {@code IMPORT}) holds the old and the new owner and the run.
     * Only a case still {@code OPEN} and still owned by the default owner changes, and only to someone who may own the
     * team's cases ({@link #eligibleOwner}); one conditional statement changes the owner, so a reassignment made
     * meanwhile wins. Without an owner it only says whether the case still waits.
     *
     * @param runId the {@code RELINK} run
     * @param caseId the case, from the importer's pending owners
     * @param defaultOwnerId the default owner the load gave it
     * @param ownerId the legacy owner's user, or {@code null} when nobody single was matched
     * @return what was done
     */
    Relinked relinkOwner(long runId, long caseId, long defaultOwnerId, @Nullable Long ownerId);

    /**
     * The SLA state an imported open case would have at one instant (WP-26 part r3, design §7.1): its case type's
     * policy started from its original dates by the one anchor rule (ADR-0007) and walked to that instant, exactly as
     * {@link #importCase} computes it at load.
     */
    @FunctionalInterface
    interface SlaForecast {

        /**
         * One open case's forecast.
         *
         * @param state its SLA state at the instant
         * @param repeatDue whether, overdue, its first overdue repeat is due by then ({@link
         *     CaseSlaService.OverdueCase#repeatPeriod}), so the load pre-marks a repeat too (WP-26 part ac)
         */
        record Forecast(SlaState state, boolean repeatDue) {}

        /**
         * The forecast of one open case.
         *
         * @param caseTypeName its case type's name
         * @param identifiedOn when the matter was identified
         * @param openedAt when the legacy case was opened
         * @return the forecast, or {@code null} for a case type Trailkeep does not have
         */
        @Nullable
        Forecast forecast(String caseTypeName, LocalDate identifiedOn, Instant openedAt);

        /**
         * The state of one open case.
         *
         * @param caseTypeName its case type's name
         * @param identifiedOn when the matter was identified
         * @param openedAt when the legacy case was opened
         * @return the state, or {@code null} for a case type Trailkeep does not have
         */
        default @Nullable SlaState state(String caseTypeName, LocalDate identifiedOn, Instant openedAt) {
            Forecast forecast = forecast(caseTypeName, identifiedOn, openedAt);
            return forecast == null ? null : forecast.state();
        }
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
     * A case an earlier load created, as a delta finds it (WP-26 part y2): its id and number, and whether it may still
     * take imported children. An archived case is read-only and a purged one a tombstone whose content the retention
     * purge removed, so neither takes any; a case on legal hold still does (a hold keeps history, it does not freeze
     * it).
     *
     * @param created its id and number
     * @param state {@code ACTIVE}, {@code ARCHIVED} or {@code PURGED}; a purged case, also archived, is {@code PURGED}
     * @param changedBy who last changed the case since its import created it, by any route (an edit, a decision,
     *     {@link #relinkOwner} as the import principal), or {@code null} when it is unchanged since (WP-26 part aa2):
     *     {@code reconcile} then compares its written form no more
     */
    record Existing(Created created, State state, @Nullable Long changedBy) {

        /** Whether the case may take imported children, and if not, why. */
        public enum State {
            /** Neither archived nor purged: it takes imported children. */
            ACTIVE,
            /** Archived ({@code ARCHIVED_AT} set), not purged: read-only. */
            ARCHIVED,
            /** Purged ({@code PURGED_AT} set): a tombstone. */
            PURGED
        }
    }

    /**
     * Cases an earlier load created, for a delta's child-only load and its dry run (WP-26 parts y1, y2, design §3.1):
     * their ids, their numbers, which the comment import API names a case by, and their state; archived and purged
     * cases take no imported children.
     *
     * @param caseIds case ids from the import ledger, at most a page's worth
     * @return those that exist, by id
     */
    Map<Long, Existing> existing(Collection<Long> caseIds);

    /**
     * Imported cases as the database holds them (WP-26 part aa1, design §9): each case's canonical written form
     * ({@code WrittenForm}), which the importer hashes into its ledger when it writes the case and again in {@code
     * reconcile}. It leaves out what later work changes in place on any case: version, change stamps, archiving, purge
     * and legal hold. Pending writes of the caller's transaction are flushed first, so they are read as stored.
     *
     * @param caseIds the cases, any number
     * @return each existing case's form, by id
     */
    Map<Long, String> writtenCases(Collection<Long> caseIds);

    /**
     * Imported comments as the database holds them, like {@link #writtenCases}; a comment is insert-only.
     *
     * @param commentIds the comments, any number
     * @return each existing comment's form, by id
     */
    Map<Long, String> writtenComments(Collection<Long> commentIds);
}
