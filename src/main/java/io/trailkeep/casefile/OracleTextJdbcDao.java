// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.sql.SQLException;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Asks the Oracle Text index of {@code CASE_SEARCH} whether a {@code CONTAINS} query can run (ADR-0006, amended
 * 2026-11-23). Oracle Text expands each {@code %run%} to the tokens containing it and refuses a query whose expansion
 * passes the word list's limit (DRG-51030, 20 000 tokens with the system word list V21 uses). Native SQL, through
 * {@link JdbcTemplate}, because the answer must not cost the caller's transaction: an error from a JPA query marks
 * the transaction for rollback, so the search could not fall back to {@code LIKE} in it, while Oracle rolls back only
 * the failed statement of a JDBC one.
 */
class OracleTextJdbcDao {

    /** Oracle Text's error for a wildcard that expands to too many tokens. */
    static final String TOO_MANY_TERMS = "DRG-51030";

    private static final String PROBE =
            "SELECT COUNT(*) FROM CASE_SEARCH WHERE CONTAINS(SEARCH_TEXT, ?) > 0 AND ROWNUM <= 1";

    private final JdbcTemplate jdbc;

    OracleTextJdbcDao(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether the index answers the query, rather than refusing it for expanding to too many tokens.
     *
     * @param contains a {@link OracleTextQuery#contains()} query, not empty
     * @return {@code false} if Oracle Text refused it with DRG-51030
     * @throws DataAccessException for any other failure
     */
    boolean answers(String contains) {
        try {
            jdbc.queryForObject(PROBE, Long.class, contains);
            return true;
        } catch (DataAccessException refused) {
            if (tooManyTerms(refused)) {
                return false;
            }
            throw refused;
        }
    }

    private static boolean tooManyTerms(Throwable refused) {
        for (@Nullable Throwable t = refused; t != null; t = t.getCause()) {
            if (t instanceof SQLException && String.valueOf(t.getMessage()).contains(TOO_MANY_TERMS)) {
                return true;
            }
        }
        return false;
    }
}
