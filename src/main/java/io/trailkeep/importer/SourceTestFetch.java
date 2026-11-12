// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.MappingProblem.Code;
import io.trailkeep.importer.SourceQueryJdbcDao.Session;
import io.trailkeep.importer.SourceQueryJdbcDao.Source;
import io.trailkeep.importer.SourceQueryValidator.ValidatedSourceQuery;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code validate-mapping}'s last step for a JDBC source (design §3 step 1, §4.1): connects as the source account and
 * test-fetches every query for at most one row, so a mapping that names a table the account cannot read, or a column
 * the source does not have, fails before any extract. Runs only on a mapping that passed every structural and query
 * check.
 *
 * <p>The account comes from {@link SourceAccount}, as the extract's does. A failure is a code from the driver's type
 * and SQLState only ({@link SourceQueryException}), at the query's place in the file.
 */
@Component
class SourceTestFetch {

    /** The statement timeout of each test fetch. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final SourceQueryJdbcDao dao;
    private final Environment environment;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    SourceTestFetch(SourceQueryJdbcDao dao, Environment environment) {
        this.dao = dao;
        this.environment = environment;
    }

    /**
     * Test-fetches a structurally sound mapping's queries.
     *
     * @param content the mapping file's bytes
     * @return the problems, by place; empty for a file source or when every query ran
     */
    List<MappingProblem> check(byte[] content) {
        JsonNode mapping = yaml.readTree(content);
        Vendor vendor = MappingValidator.vendor(mapping);
        if (vendor == null) {
            return List.of();
        }
        if (vendor == Vendor.SQLSERVER) {
            return List.of(new MappingProblem(Code.SOURCE_UNSUPPORTED, "/source/vendor"));
        }
        Source source;
        try {
            source = SourceAccount.of(mapping, vendor, environment);
        } catch (SourceException refused) {
            String pointer = "SOURCE_URL_NOT_ALLOWED".equals(refused.code()) ? "/source/url" : "/source";
            return List.of(new MappingProblem(Code.valueOf(refused.code()), pointer));
        }
        return fetchAll(mapping, source);
    }

    /** Every query fetched for at most one row in one session; each failure a problem at its place. */
    private List<MappingProblem> fetchAll(JsonNode mapping, Source source) {
        Set<MappingProblem> problems = new TreeSet<>();
        try (Session session = dao.open(source)) {
            for (String pointer : MappingValidator.queryPointers(mapping)) {
                ValidatedSourceQuery query = SourceQueryValidator.validate(
                                mapping.at(pointer).asString(), source.vendor())
                        .query();
                if (query == null) {
                    throw new IllegalStateException("only a mapping whose queries all validate is test-fetched");
                }
                try {
                    dao.testFetch(session, query, TIMEOUT);
                } catch (SourceQueryException e) {
                    problems.add(new MappingProblem(Code.valueOf(e.code()), pointer));
                }
            }
        } catch (SourceQueryException e) {
            problems.add(new MappingProblem(Code.valueOf(e.code()), "/source"));
        }
        return List.copyOf(problems);
    }
}
