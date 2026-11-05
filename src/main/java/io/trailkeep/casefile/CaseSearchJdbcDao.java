// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.persistence.DatabaseProperties;
import io.trailkeep.shared.persistence.DatabaseVendor;
import java.io.StringReader;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Appends a comment's text to a case's {@code CASE_SEARCH} row in one statement (BUILD_PLAN §8 rule 4, ADR-0006).
 * Native SQL because JPA can only read the row and write it back, which loses a concurrent writer's text unless the
 * row is locked first; {@code SEARCH_TEXT = SEARCH_TEXT || ?} is atomic under the database's own row lock, so two
 * comments posted at once both land. The statement is constant. A plain string bind over 4000 bytes inside an Oracle
 * expression fails with ORA-01461, and so does a character stream over 32 KB, which the Oracle driver sends as
 * {@code LONG}; so on Oracle the fragment is bound as a {@code CLOB} read from a reader. PostgreSQL's driver has no
 * such bind ({@code setClob} means a large object there) and needs none, so it gets the character stream.
 */
@Repository
class CaseSearchJdbcDao {

    private static final String APPEND =
            "UPDATE CASE_SEARCH SET SEARCH_TEXT = SEARCH_TEXT || ?, UPDATED_AT = ? WHERE CASE_ID = ?";

    private final JdbcTemplate jdbc;
    private final boolean oracle;

    CaseSearchJdbcDao(JdbcTemplate jdbc, DatabaseProperties database) {
        this.jdbc = jdbc;
        this.oracle = database.vendor() == DatabaseVendor.ORACLE;
    }

    /**
     * Appends text to a case's search text, in the caller's transaction.
     *
     * @param caseId the case
     * @param fragment the text to append, separator included
     * @param now when it was written
     * @return {@code true} if the case had a search row to append to
     */
    boolean append(long caseId, String fragment, Instant now) {
        return jdbc.update(APPEND, statement -> {
                    if (oracle) {
                        statement.setClob(1, new StringReader(fragment), fragment.length());
                    } else {
                        statement.setCharacterStream(1, new StringReader(fragment), fragment.length());
                    }
                    statement.setObject(2, OffsetDateTime.ofInstant(now, ZoneOffset.UTC));
                    statement.setLong(3, caseId);
                })
                == 1;
    }
}
