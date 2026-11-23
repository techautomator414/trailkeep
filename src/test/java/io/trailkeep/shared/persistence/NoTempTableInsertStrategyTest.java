// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.metamodel.mapping.internal.MappingModelCreationProcess;
import org.hibernate.query.spi.DomainQueryExecutionContext;
import org.hibernate.query.sqm.internal.DomainParameterXref;
import org.hibernate.query.sqm.tree.insert.SqmInsertStatement;
import org.junit.jupiter.api.Test;

class NoTempTableInsertStrategyTest {

    private final NoTempTableInsertStrategy strategy = new NoTempTableInsertStrategy();

    @Test
    void anInsertSelectInHqlIsRefusedWithTheReasonInsteadOfFailingOnAMissingTable() {
        assertThatThrownBy(() -> strategy.buildHandler(
                        mock(SqmInsertStatement.class),
                        mock(DomainParameterXref.class),
                        mock(DomainQueryExecutionContext.class)))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("issue #317");
    }

    @Test
    void preparingAndReleasingCreateAndDropNothing() {
        JdbcConnectionAccess access = mock(JdbcConnectionAccess.class);
        strategy.prepare(mock(MappingModelCreationProcess.class), access);
        strategy.release(mock(SessionFactoryImplementor.class), access);
    }
}
