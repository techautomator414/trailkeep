// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import io.trailkeep.shared.persistence.WrittenForm;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The written forms of imported legacy history (WP-26 part aa1, docs/design/importer.md §9): every column of an
 * insert-only {@code CASE_IMPORTED_EVENT} row, its redacted values as their keyed hashes and lengths.
 * Native SQL because a form must be what the database holds, which a JPA read in the same session does not give
 * (see {@code WrittenForm}); the statement is constant and reads CLOBs whole, with no SQL function on them.
 */
@Repository
class ImportedEventWrittenJdbcDao {

    private static final String SQL =
            "SELECT ID, CASE_ID, AT_TS, WHO_NAME_SNAPSHOT, KIND, FIELD_NAME, OLD_VALUE, NEW_VALUE,"
                    + " OLD_HMAC, NEW_HMAC, OLD_LENGTH, NEW_LENGTH, RUN_ID FROM CASE_IMPORTED_EVENT WHERE ID IN (:ids)";

    private final NamedParameterJdbcTemplate jdbc;

    ImportedEventWrittenJdbcDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The forms; the caller has flushed its transaction's pending writes. */
    Map<Long, String> events(Collection<Long> ids) {
        Map<Long, String> forms = new HashMap<>();
        for (List<Long> chunk : WrittenForm.chunks(ids)) {
            jdbc.query(
                    SQL,
                    Map.of("ids", chunk),
                    WrittenForm.into(
                            forms,
                            "ID",
                            form -> form.texts(
                                            "WHO_NAME_SNAPSHOT",
                                            "KIND",
                                            "FIELD_NAME",
                                            "OLD_VALUE",
                                            "NEW_VALUE",
                                            "OLD_HMAC",
                                            "NEW_HMAC")
                                    .numbers("CASE_ID", "OLD_LENGTH", "NEW_LENGTH", "RUN_ID")
                                    .instants("AT_TS")));
        }
        return forms;
    }
}
