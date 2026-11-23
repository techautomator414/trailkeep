// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The probe tells Oracle Text's "too many terms" apart from every other failure, which it passes on, and asks once per
 * transaction.
 */
class OracleTextJdbcDaoTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final OracleTextJdbcDao index = new OracleTextJdbcDao(jdbc);

    @Test
    void queriesTheIndexRunsAreAnswered() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);

        assertThat(index.answers("%kestrel%")).isTrue();
    }

    @Test
    void tooManyTermsIsNotAnswered() {
        SQLException refused = new SQLException(
                "ORA-29902: error in executing ODCIIndexStart() routine\nORA-20000: Oracle Text error:\n"
                        + "DRG-51030: wildcard query expansion resulted in too many terms",
                "99999",
                29902);
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(new UncategorizedSQLException("probe", "SELECT", refused));

        assertThat(index.answers("%qzx%")).isFalse();
    }

    @Test
    void oneTransactionAsksOnceAndForgetsTheAnswerWhenItEnds() {
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenReturn(1L);
        TransactionSynchronizationManager.initSynchronization();
        try {
            assertThat(index.answers("%kestrel%")).isTrue();
            assertThat(index.answers("%kestrel%")).isTrue();
            verify(jdbc, times(1)).queryForObject(anyString(), eq(Long.class), any(Object[].class));

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(synchronization ->
                            synchronization.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));
            assertThat(TransactionSynchronizationManager.hasResource(index)).isFalse();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void anyOtherFailureIsPassedOn() {
        DataAccessResourceFailureException down = new DataAccessResourceFailureException(
                "down", new SQLException("ORA-03113: end-of-file on communication channel"));
        when(jdbc.queryForObject(anyString(), eq(Long.class), any(Object[].class)))
                .thenThrow(down);

        assertThatExceptionOfType(DataAccessResourceFailureException.class)
                .isThrownBy(() -> index.answers("%kestrel%"))
                .isSameAs(down);
    }
}
