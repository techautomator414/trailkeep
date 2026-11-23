// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Asks the Oracle Text index of {@code CASE_SEARCH} whether a {@code CONTAINS} query can run (ADR-0017). Oracle Text
 * expands each {@code %run%} to the tokens containing it and refuses a query whose expansion passes the word list's
 * limit (DRG-51030, 20 000 tokens with the system word list V21 uses). Native SQL, through {@link JdbcTemplate},
 * because the answer must not cost the caller's transaction: an error from a JPA query marks the transaction for
 * rollback, so the search could not fall back to {@code LIKE} in it, while Oracle rolls back only the failed statement
 * of a JDBC one. An answer is kept for the rest of the transaction, so the list's count and page, which build the
 * same predicate, ask once.
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
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return probe(contains);
        }
        Answers answers = TransactionSynchronizationManager.getResource(this) instanceof Answers bound ? bound : bind();
        return answers.byQuery.computeIfAbsent(contains, this::probe);
    }

    private boolean probe(String contains) {
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

    private Answers bind() {
        Answers answers = new Answers();
        TransactionSynchronizationManager.bindResource(this, answers);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                TransactionSynchronizationManager.unbindResourceIfPossible(OracleTextJdbcDao.this);
            }
        });
        return answers;
    }

    private static boolean tooManyTerms(Throwable refused) {
        for (@Nullable Throwable t = refused; t != null; t = t.getCause()) {
            if (t instanceof SQLException && String.valueOf(t.getMessage()).contains(TOO_MANY_TERMS)) {
                return true;
            }
        }
        return false;
    }

    /** The answers of one transaction, by query. */
    private static final class Answers {
        private final Map<String, Boolean> byQuery = new HashMap<>();
    }
}
