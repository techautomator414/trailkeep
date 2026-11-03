// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.shared.access.CaseRooted;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.QueryByExampleExecutor;

/** Violation: a Spring Data executor interface other than {@code Repository} adds unscoped queries. */
@CaseRooted
interface ExampleExecutorCaseRepository
        extends Repository<FixtureEntity, Long>, QueryByExampleExecutor<FixtureEntity> {}
