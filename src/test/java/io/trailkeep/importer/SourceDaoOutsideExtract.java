// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.SourceQueryJdbcDao.Session;
import io.trailkeep.importer.SourceQueryJdbcDao.Source;

/**
 * A planted violation for {@code SourceQueryDaoRulesTest}: an importer class outside extract and the test fetch that
 * opens a source session. It sits in the importer package because the DAO is package-private; it is never a bean and
 * never runs.
 */
final class SourceDaoOutsideExtract {

    private final SourceQueryJdbcDao dao;

    SourceDaoOutsideExtract(SourceQueryJdbcDao dao) {
        this.dao = dao;
    }

    Session open(Source source) {
        return dao.open(source);
    }
}
