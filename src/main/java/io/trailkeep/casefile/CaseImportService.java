// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Creates imported cases (WP-26 part e, docs/design/importer.md §7.1, §8.1). Called only by {@code importer}
 * (ArchUnit), never on a request thread. It keeps the rules of {@link CaseService#create} except the relaxations of
 * §8.1 R6, each tested by name:
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
     * Creates the case with its SLA row and search text, and audits it, in the caller's transaction.
     *
     * @param run the run
     * @param imported the case
     * @return its new Trailkeep number
     * @throws CaseException {@code INVALID} for a case the rules refuse
     */
    CaseNumber importCase(ImportRun run, ImportedCase imported);
}
