// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Records how an imported closed case was closed (WP-26 part h, docs/design/importer.md §7.1, §8.1). Called only by
 * {@code importer} (ArchUnit), in the case's load transaction. The one relaxation (R6 8, {@code
 * importedClosureSkipsApproverEligibilityOnly}): the request is written already {@code APPROVED}, requested by the
 * "Trailkeep import" principal and decided by the "Legacy approval (imported)" principal, who did not act, at the
 * legacy closing time, and the approver-eligibility check of BUILD_PLAN §3.2 is skipped. Requester and approver still
 * differ ({@code CK_CLOSURE_SOD}), and no case is ever owned by the legacy-approval principal, so owner and approver
 * differ too. No real person is recorded as deciding a closure made years before: the run's approver goes into the
 * audit details only.
 */
public interface ClosureImportService {

    /** What an imported closure says when the legacy record has no summary (design §7.1). */
    String NO_SUMMARY = "(no resolution summary in the legacy record)";

    /**
     * One legacy closure.
     *
     * @param caseId the imported closed case
     * @param outcome the legacy outcome, mapped onto one of the case type's outcomes by the importer
     * @param resolutionSummary the legacy summary, or {@code null} for {@link #NO_SUMMARY}
     * @param closedAt when the legacy case was closed: the request's time and its decision's
     * @param runId the import run
     * @param runApproverId the Compliance Admin who signed the run off, for the audit details
     */
    record ImportedClosure(
            long caseId,
            String outcome,
            @Nullable String resolutionSummary,
            Instant closedAt,
            long runId,
            long runApproverId) {}

    /**
     * Writes the approved closure request, marked imported, and audits it as {@code CLOSURE_IMPORTED}.
     *
     * @param closure the closure
     * @return the request's id
     * @throws ClosureException {@code INVALID} for a blank or over-long outcome, an over-long summary or a future time
     */
    long importClosure(ImportedClosure closure);
}
