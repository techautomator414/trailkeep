// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.closure;

import io.trailkeep.shared.persistence.WrittenForm;
import java.util.Collection;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The written forms of imported closures (WP-26 part aa1, docs/design/importer.md §9), by case: a case has at most
 * one closure request marked {@code IMPORTED}, the one the load wrote, so the case id finds it exactly. Insert-only
 * as far as the form goes: an imported closure is approved when written and never decided again.
 * Native SQL because a form must be what the database holds, which a JPA read in the same session does not give
 * (see {@code WrittenForm}); the statement is constant and reads CLOBs whole, with no SQL function on them.
 */
@Repository
class ClosureWrittenJdbcDao {

    private static final String SQL =
            "SELECT CASE_ID, REQUESTED_BY, APPROVER_ID, REQUESTED_AT, OUTCOME, RESOLUTION_SUMMARY,"
                    + " FOLLOW_UP_ACTIONS, DECISION, DECIDED_BY, DECIDED_AT, REASON FROM CLOSURE_REQUEST"
                    + " WHERE CASE_ID IN (:ids) AND IMPORTED = :imported";

    private final NamedParameterJdbcTemplate jdbc;

    ClosureWrittenJdbcDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The forms; the caller has flushed its transaction's pending writes. */
    Map<Long, String> byCase(Collection<Long> ids) {
        return WrittenForm.read(
                jdbc,
                SQL,
                "CASE_ID",
                ids,
                Map.of("imported", true),
                form -> form.texts("OUTCOME", "RESOLUTION_SUMMARY", "FOLLOW_UP_ACTIONS", "DECISION", "REASON")
                        .numbers("REQUESTED_BY", "APPROVER_ID", "DECIDED_BY")
                        .instants("REQUESTED_AT", "DECIDED_AT"));
    }
}
