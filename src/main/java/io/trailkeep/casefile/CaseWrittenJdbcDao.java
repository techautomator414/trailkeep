// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.persistence.WrittenForm;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The written forms of imported cases and comments (WP-26 part aa1, docs/design/importer.md §9), for the importer's
 * written hashes. Native SQL because a form must be what the database holds, which a JPA read in the same session does
 * not give: it returns the persistence context's cached entities with their Java values, while Oracle reads an empty
 * string back as {@code NULL} and keeps instants to the microsecond. The statements are constant and read whole CLOBs,
 * with no SQL function on them. A case's form leaves out what later work changes in place on any case (version,
 * change stamps, archiving, purge, legal hold); a comment is insert-only.
 */
@Repository
class CaseWrittenJdbcDao {

    private static final String CASES = "SELECT ID, CASE_NUMBER, TITLE, DESCRIPTION, SUBJECT_TEXT, CASE_TYPE_ID,"
            + " TEAM_ID, OWNER_ID, PRIORITY, STATUS, SENSITIVITY, IDENTIFIED_ON, OPENED_AT, CLOSED_AT, LEGACY_REF,"
            + " LEGACY_NUMBER, OWNER_NAME_SNAPSHOT, CREATED_BY FROM CASE_RECORD WHERE ID IN (:ids)";
    private static final String COMMENTS = "SELECT ID, CASE_ID, BODY, AUTHOR_ID, AUTHOR_NAME_SNAPSHOT, CREATED_AT"
            + " FROM CASE_COMMENT WHERE ID IN (:ids)";

    private final NamedParameterJdbcTemplate jdbc;

    CaseWrittenJdbcDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Case forms; the caller has flushed its transaction's pending writes. */
    Map<Long, String> cases(Collection<Long> ids) {
        Map<Long, String> forms = new HashMap<>();
        for (List<Long> chunk : WrittenForm.chunks(ids)) {
            jdbc.query(
                    CASES,
                    Map.of("ids", chunk),
                    WrittenForm.into(
                            forms,
                            "ID",
                            form -> form.texts(
                                            "CASE_NUMBER",
                                            "TITLE",
                                            "DESCRIPTION",
                                            "SUBJECT_TEXT",
                                            "PRIORITY",
                                            "STATUS",
                                            "SENSITIVITY",
                                            "LEGACY_REF",
                                            "LEGACY_NUMBER",
                                            "OWNER_NAME_SNAPSHOT")
                                    .numbers("CASE_TYPE_ID", "TEAM_ID", "OWNER_ID", "CREATED_BY")
                                    .dates("IDENTIFIED_ON")
                                    .instants("OPENED_AT", "CLOSED_AT")));
        }
        return forms;
    }

    /** Comment forms; the caller has flushed its transaction's pending writes. */
    Map<Long, String> comments(Collection<Long> ids) {
        Map<Long, String> forms = new HashMap<>();
        for (List<Long> chunk : WrittenForm.chunks(ids)) {
            jdbc.query(
                    COMMENTS,
                    Map.of("ids", chunk),
                    WrittenForm.into(
                            forms,
                            "ID",
                            form -> form.texts("BODY", "AUTHOR_NAME_SNAPSHOT")
                                    .numbers("CASE_ID", "AUTHOR_ID")
                                    .instants("CREATED_AT")));
        }
        return forms;
    }
}
