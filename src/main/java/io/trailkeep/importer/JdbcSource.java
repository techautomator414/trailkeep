// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.SourceQueryJdbcDao.Session;
import io.trailkeep.importer.SourceQueryValidator.ValidatedSourceQuery;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The PostgreSQL source (WP-26 part n2, design §4.1, §4.3): a {@code kind: jdbc} mapping read through {@link
 * SourceQueryJdbcDao} as the account {@link SourceAccount} resolves, in one {@code REPEATABLE READ READ ONLY} snapshot
 * that every entity query and every attachment content query shares. The fingerprint is the hash of that snapshot. It
 * dies with the connection, so an extract that crashed meets another fingerprint and starts over, as §4.1 "Restart"
 * says it must. Each query is validated again before it runs; the run's mapping hash already proves it is the file
 * {@code validate-mapping} passed. Oracle and SQL Server arrive with 26o and 26p.
 */
@Component
@Profile("importer")
final class JdbcSource implements SourceRows.Factory {

    /** The statement timeout of an entity query, which reads the whole entity. */
    static final Duration QUERY_TIMEOUT = Duration.ofHours(1);

    private final SourceQueryJdbcDao dao;
    private final Environment environment;

    JdbcSource(SourceQueryJdbcDao dao, Environment environment) {
        this.dao = dao;
        this.environment = environment;
    }

    @Override
    public boolean supports(JsonNode mapping) {
        return MappingValidator.vendor(mapping) == Vendor.POSTGRESQL;
    }

    @Override
    public SourceRows open(JsonNode mapping, Path directory) {
        return new OpenSession(dao.open(SourceAccount.of(mapping, Vendor.POSTGRESQL, environment)));
    }

    /** One snapshot of the source, for one extract. */
    private final class OpenSession implements SourceRows {

        private final Session session;

        OpenSession(Session session) {
            this.session = session;
        }

        @Override
        public String fingerprint() {
            // The snapshot's text lists every transaction in progress, so it can be long; its hash is not.
            return "postgresql:"
                    + ValidateMappingCommand.sha256(dao.fingerprint(session).getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
            dao.stream(session, query(spec.path("query").asString()), List.of(), QUERY_TIMEOUT, rows);
        }

        @Override
        public List<Map<String, @Nullable Object>> contentRows(String query, List<@Nullable Object> key) {
            return dao.fetch(session, query(query), key, SourceTestFetch.TIMEOUT, 2);
        }

        @Override
        public void close() {
            session.close();
        }
    }

    private static ValidatedSourceQuery query(String sql) {
        SourceQueryValidator.Result result = SourceQueryValidator.validate(sql, Vendor.POSTGRESQL);
        ValidatedSourceQuery query = result.query();
        if (query == null) {
            throw new SourceException(result.problems().getFirst().name(), null);
        }
        return query;
    }
}
