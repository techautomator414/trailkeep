// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.user.UserPermissions;
import java.io.IOException;
import java.io.OutputStream;

/**
 * CSV export of the cases list (BUILD_PLAN §4.4 "Export CSV", §3.3). An export holds every case the actor's scope
 * reads that matches the list's filter, in the list's order, through the same {@code WHERE} as the list, so a case
 * missing from the list is missing from the export. The subject and the description are replaced by {@link
 * #REDACTED} unless the actor asks for details and is a Manager of the case's team or a Compliance Admin; for anyone
 * else a request for details is ignored, not refused. Every export is audited ({@code CASES_EXPORTED}) with its row
 * count, its query and whether details were included.
 */
public interface CaseExportService {

    /** What a redacted subject or description reads. */
    String REDACTED = "[redacted]";

    /** What an export request gave. */
    sealed interface Prepared {}

    /** An export ready to stream; it has been audited. */
    non-sealed interface Ready extends Prepared {

        /**
         * How many cases match, and so how many rows the export holds at most.
         *
         * @return the count
         */
        long rows();

        /**
         * Whether the export includes details where the actor may see them.
         *
         * @return {@code true} with details
         */
        boolean details();

        /**
         * Writes the CSV, a byte order mark first, in pages inside one read-only transaction.
         *
         * @param out where to write; not closed
         * @throws IOException if writing fails
         */
        void writeTo(OutputStream out) throws IOException;
    }

    /**
     * The filter matches more cases than one export may hold; nothing was exported or audited.
     *
     * @param rows how many cases match
     * @param maxRows the most one export may hold ({@code app.export.max-rows})
     */
    record TooMany(long rows, int maxRows) implements Prepared {}

    /**
     * Prepares an export of the list a query describes, from its first row.
     *
     * @param actor the actor
     * @param query the list's filter and order; the page is ignored
     * @param details whether the actor asks for the subject and the description
     * @return the export, or why there is none
     */
    Prepared export(Actor actor, CaseListQuery query, boolean details);

    /**
     * Whether "Include details" is offered: the person is a Manager in some team or a Compliance Admin.
     *
     * @param user the person's permissions
     * @return {@code true} if they may include details for at least some cases
     */
    boolean offersDetails(UserPermissions user);
}
