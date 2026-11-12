// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.persistence.DatabaseProperties;
import jakarta.persistence.EntityManagerFactory;
import java.util.Objects;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Draws case numbers from {@code SEQ_CASE_NUMBER}, one value per case and never pooled, so numbers have no hot row
 * and no lock and may have gaps (BUILD_PLAN §4.2, §8 rule 6). Native SQL because JPA has no way to read a sequence
 * that backs no entity id; the statement comes from the configured Hibernate dialect's sequence support, so it is
 * {@code nextval('…')} on PostgreSQL and {@code … .nextval FROM dual} on Oracle, and holds no user input. The name
 * is qualified with {@code app.db.schema} when it is set, as Hibernate qualifies every mapped table with it: a user
 * that does not own the schema (the runtime and import users, BUILD_PLAN §8 rule 7) would not find it unqualified on
 * Oracle.
 */
@Repository
class CaseNumberJdbcDao {

    private static final String SEQUENCE = "SEQ_CASE_NUMBER";

    private final JdbcClient jdbc;
    private final EntityManagerFactory entityManagerFactory;
    private final String sequence;

    CaseNumberJdbcDao(JdbcClient jdbc, EntityManagerFactory entityManagerFactory, DatabaseProperties database) {
        this.jdbc = jdbc;
        this.entityManagerFactory = entityManagerFactory;
        String schema = database.schema();
        this.sequence = schema == null || schema.isBlank() ? SEQUENCE : schema.strip() + "." + SEQUENCE;
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
                .getSequenceNextValString(sequence);
        return Objects.requireNonNull(jdbc.sql(nextValue).query(Long.class).single());
    }
}
