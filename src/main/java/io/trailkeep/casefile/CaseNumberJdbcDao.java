// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Draws case numbers from {@code SEQ_CASE_NUMBER}, one value per case and never pooled, so numbers have no hot row
 * and no lock and may have gaps (BUILD_PLAN §4.2, §8 rule 6). Native SQL because JPA has no way to read a sequence
 * that backs no entity id; the statement comes from the configured Hibernate dialect's sequence support, so it is
 * {@code nextval('…')} on PostgreSQL and {@code … .nextval FROM dual} on Oracle, and holds no user input. The plain
 * name resolves in {@code app.db.schema} for users that do not own it, because that is every pooled connection's
 * default schema.
 */
@Repository
class CaseNumberJdbcDao {

    private static final String SEQUENCE = "SEQ_CASE_NUMBER";

    private final JdbcClient jdbc;
    private final EntityManagerFactory entityManagerFactory;

    CaseNumberJdbcDao(JdbcClient jdbc, EntityManagerFactory entityManagerFactory) {
        this.jdbc = jdbc;
        this.entityManagerFactory = entityManagerFactory;
    }

    /**
     * The next value of the case number sequence.
     *
     * @return a value never handed out before
     */
    long next() {
        String nextValue = entityManagerFactory
                .unwrap(SessionFactoryImplementor.class)
                .getJdbcServices()
                .getDialect()
                .getSequenceSupport()
                .getSequenceNextValString(SEQUENCE);
        return Objects.requireNonNull(jdbc.sql(nextValue).query(Long.class).single());
    }
}
