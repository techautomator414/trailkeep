// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.internal.MappingModelCreationProcess;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.mutation.spi.MultiTableHandlerBuildResult;
import org.hibernate.query.sqm.mutation.spi.SqmMultiTableInsertStrategy;
import org.hibernate.query.sqm.tree.insert.SqmInsertStatement;

/**
 * Hibernate's insert strategy for Oracle: none. An entity whose id comes from a pooled sequence (an allocation size
 * above one) needs a multi-table insert strategy for an HQL {@code insert ... select}, because the ids cannot be drawn
 * in the same statement. On Oracle Hibernate's choice is {@code GlobalTemporaryTableInsertStrategy}, which creates one
 * {@code HTE_*} global temporary table per such entity when the session factory starts. That is DDL outside Flyway
 * (issue #317), and only the owner's start-up could run it. Trailkeep has no HQL {@code insert ... select}, so no such
 * table is ever read or written; this strategy takes the place of the tables and refuses the statement, instead of
 * failing later on a missing table. Configured for Oracle only, in {@code application-oracle.yml}: PostgreSQL's choice
 * is a CTE and needs no table. A statement that does need one should be a native or JDBC statement, or this decision
 * revisited.
 */
public final class NoTempTableInsertStrategy implements SqmMultiTableInsertStrategy {

    private static final String REFUSED = "HQL insert ... select on an entity with a pooled-sequence id is not "
            + "supported on Oracle: Hibernate would need a temporary table, and Flyway owns the schema (issue #317). "
            + "Use a native or JDBC statement.";

    @Override
    public void prepare(MappingModelCreationProcess process, JdbcConnectionAccess connectionAccess) {
        // Nothing to create: that is the point of this strategy.
    }

    @Override
    public void release(SessionFactoryImplementor sessionFactory, JdbcConnectionAccess connectionAccess) {
        // Nothing was created, so nothing to drop.
    }

    @Override
    public MultiTableHandlerBuildResult buildHandler(
            SqmInsertStatement<?> sqmInsertStatement,
            DomainParameterXref domainParameterXref,
            DomainQueryExecutionContext context) {
        throw new UnsupportedOperationException(REFUSED);
    }
}
