// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.SourceQueryJdbcDao.Session;
import io.trailkeep.importer.SourceQueryValidator.ValidatedSourceQuery;
import io.trailkeep.importer.SourceQueryValidator.Vendor;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * The PostgreSQL (WP-26 part n2) and Oracle (26o) source (design §4.1, §4.3): a {@code kind: jdbc} mapping read
 * through {@link SourceQueryJdbcDao} as the account {@link SourceAccount} resolves, in one read-only snapshot that
 * every entity query and every attachment content query shares. The fingerprint names that snapshot. It dies with the
 * connection, so an extract that crashed meets another fingerprint and starts over, as §4.1 "Restart" says it must.
 * Each query is validated again for the vendor before it runs; the run's mapping hash already proves it is the file
 * {@code validate-mapping} passed. SQL Server arrives with 26p.
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
        Vendor vendor = MappingValidator.vendor(mapping);
        return vendor == Vendor.POSTGRESQL || vendor == Vendor.ORACLE;
    }

    @Override
    public SourceRows open(JsonNode mapping, Path directory) {
        Vendor vendor = Objects.requireNonNull(MappingValidator.vendor(mapping), "a JDBC mapping has a vendor");
        return new OpenSession(dao.open(SourceAccount.of(mapping, vendor, environment)), vendor);
    }

    /** One snapshot of the source, for one extract. */
    private final class OpenSession implements SourceRows {

        private final Session session;
        private final Vendor vendor;

        OpenSession(Session session, Vendor vendor) {
            this.session = session;
            this.vendor = vendor;
        }

        @Override
        public String fingerprint() {
            return dao.fingerprint(session);
        }

        @Override
        public void read(StagedEntity entity, JsonNode spec, Consumer<Map<String, @Nullable Object>> rows) {
            dao.stream(session, query(spec.path("query").asString(), vendor), List.of(), QUERY_TIMEOUT, rows);
        }

        @Override
        public List<Map<String, @Nullable Object>> contentRows(String query, List<@Nullable Object> key) {
            return dao.fetch(session, query(query, vendor), key, SourceTestFetch.TIMEOUT, 2);
        }

        @Override
        public void close() {
            session.close();
        }
    }

    private static ValidatedSourceQuery query(String sql, Vendor vendor) {
        SourceQueryValidator.Result result = SourceQueryValidator.validate(sql, vendor);
        ValidatedSourceQuery query = result.query();
        if (query == null) {
            throw new SourceException(result.problems().getFirst().name(), null);
        }
        return query;
    }
}
