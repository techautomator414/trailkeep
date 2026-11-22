// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.report;

import io.trailkeep.audit.AuditTrailService.AuditFilter;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.access.CaseScope;
import java.io.IOException;
import java.io.OutputStream;

/**
 * CSV export of the audit trail for Compliance Admins (BUILD_PLAN §9 "audit export for Compliance Admins", #274): the
 * events the audit screen's filter keeps, newest first, up to {@code app.export.max-rows}, the cases list export's
 * bound. Each export is audited ({@code AUDIT_EXPORTED}) with its row count and filter, in the transaction that counts
 * it. A redacted value is written as "redacted (n characters)", never as its HMAC; every cell goes through {@link
 * io.trailkeep.shared.csv.CsvFormat}, so a cell that would start a formula is defused. The caller passes the scope the
 * security module granted.
 */
public interface AuditExportService {

    /** What an export request gave. */
    sealed interface Prepared {}

    /** An export ready to stream; it has been audited. */
    non-sealed interface Ready extends Prepared {

        /**
         * How many events it holds.
         *
         * @return the count
         */
        long rows();

        /**
         * Writes the CSV, a byte order mark first, in pages inside one read-only transaction.
         *
         * @param out where to write; not closed
         * @throws IOException if writing fails
         */
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * The filter matches more events than one export may hold; nothing was exported or audited.
     *
     * @param rows how many events match
     * @param maxRows the most one export may hold ({@code app.export.max-rows})
     */
    record TooMany(long rows, int maxRows) implements Prepared {}

    /**
     * Counts and audits an export of the events a filter keeps, as far as now: an event recorded later, this export's
     * own audit event included, is not in it.
     *
     * @param actor who exports
     * @param scope the scope the security module granted for the audit trail
     * @param filter the screen's filter
     * @param query the screen's query string, kept in the audit event
     * @return the export, or why there is none
     */
    Prepared export(Actor actor, CaseScope scope, AuditFilter filter, String query);
}
