// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import io.trailkeep.shared.persistence.WrittenForm;
import java.util.Collection;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The written forms of imported attachments (WP-26 part aa1, docs/design/importer.md §9): the file's name, type,
 * size, SHA-256, upload and uploader. The scan status and its release are left out, because releasing a quarantined
 * file changes them in place; the bytes are covered by the SHA-256 the intake checked.
 * Native SQL because a form must be what the database holds, which a JPA read in the same session does not give
 * (see {@code WrittenForm}); the statement is constant and reads CLOBs whole, with no SQL function on them.
 */
@Repository
class AttachmentWrittenJdbcDao {

    private static final String SQL =
            "SELECT ID, CASE_ID, FILE_NAME, CONTENT_TYPE, SIZE_BYTES, SHA256, UPLOADED_AT, UPLOADED_BY,"
                    + " UPLOADER_NAME_SNAPSHOT FROM CASE_ATTACHMENT WHERE ID IN (:ids)";

    private final NamedParameterJdbcTemplate jdbc;

    AttachmentWrittenJdbcDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** The forms; the caller has flushed its transaction's pending writes. */
    Map<Long, String> attachments(Collection<Long> ids) {
        return WrittenForm.read(
                jdbc,
                SQL,
                "ID",
                ids,
                Map.of(),
                form -> form.texts("FILE_NAME", "CONTENT_TYPE", "SHA256", "UPLOADER_NAME_SNAPSHOT")
                        .numbers("CASE_ID", "SIZE_BYTES", "UPLOADED_BY")
                        .instants("UPLOADED_AT"));
    }
}
