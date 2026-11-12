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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
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
 * <p>{@code source.url} and {@code source.user} are resolved here, and only from {@code import.source.*} names (§10;
 * {@link MappingValidator} has already refused any other placeholder). The password is {@code import.source.password},
 * a Secret file read through {@code configtree}, never named in the mapping. A failure is a code from the driver's
 * type and SQLState only ({@link SourceQueryException}), at the query's place in the file.
 */
@Component
class SourceTestFetch {

    /** The statement timeout of each test fetch. */
    static final Duration TIMEOUT = Duration.ofSeconds(30);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(import\\.source\\.[a-z][a-z0-9.-]*)}");

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
        if (vendor != Vendor.POSTGRESQL) {
            return List.of(new MappingProblem(Code.SOURCE_UNSUPPORTED, "/source/vendor"));
        }
        String url = resolve(mapping.at("/source/url").asString());
        String user = resolve(mapping.at("/source/user").asString());
        String password = environment.getProperty("import.source.password");
        if (url == null || user == null || password == null) {
            return List.of(new MappingProblem(Code.SOURCE_SETTING_MISSING, "/source"));
        }
        if (!SourceUrl.allowed(vendor, url)) {
            // Refused before any connection: a driver URL can name classes to load and files to write.
            return List.of(new MappingProblem(Code.SOURCE_URL_NOT_ALLOWED, "/source/url"));
        }
        return fetchAll(mapping, new Source(url, user, password, vendor));
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

    /** The text with each {@code import.source.*} placeholder replaced; {@code null} if one is not configured. */
    private @Nullable String resolve(String text) {
        Matcher placeholders = PLACEHOLDER.matcher(text);
        StringBuilder resolved = new StringBuilder();
        while (placeholders.find()) {
            String value = environment.getProperty(placeholders.group(1));
            if (value == null) {
                return null;
            }
            placeholders.appendReplacement(resolved, Matcher.quoteReplacement(value));
        }
        placeholders.appendTail(resolved);
        return resolved.toString();
    }
}
