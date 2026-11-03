// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema.lint;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.schema.lint.MigrationLinter.Rule;
import io.trailkeep.schema.lint.MigrationLinter.Violation;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Runs {@link MigrationLinter} over every migration on the classpath (main and test-only) and proves each rule with
 * fixtures under {@code src/test/resources/migration-lint/}. A {@code bad/} fixture declares the findings it must
 * produce on lines starting {@code -- expect: RULE TOKEN}; the lint must report exactly those, no more, no fewer.
 */
class MigrationLintTest {

    private static final PathMatchingResourcePatternResolver RESOLVER = new PathMatchingResourcePatternResolver();
    private static final String EXPECT = "-- expect:";

    private final MigrationLinter linter = new MigrationLinter(reservedWords());

    @Test
    void everyMigrationOnTheClasspathPassesTheLint() throws IOException {
        Resource[] migrations = RESOLVER.getResources("classpath*:db/migration/**/*.sql");
        assertThat(migrations)
                .as("the scan must see at least the test-only probe migration, or it proves nothing")
                .extracting(Resource::getFilename)
                .contains("V0_1__lint_probe.sql");

        List<Violation> violations = new ArrayList<>();
        for (Resource migration : migrations) {
            violations.addAll(linter.lint(String.valueOf(migration.getFilename()), read(migration)));
        }

        assertThat(violations).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("goodFixtures")
    void portableScriptsPass(String name, String sql) {
        assertThat(linter.lint(name, sql)).isEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("badFixtures")
    void eachViolationIsReported(String name, String sql) {
        Set<String> expected = expectations(sql);
        assertThat(expected).as("a bad fixture must declare what it plants").isNotEmpty();

        assertThat(linter.lint(name, sql))
                .extracting(violation -> violation.rule() + " " + violation.token())
                .containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void findingsCarryTheLineOfTheOffendingToken() {
        String sql = "CREATE TABLE T_OK (\n  ID ${id} NOT NULL,\n  COMMENT VARCHAR(10)\n);\n";

        assertThat(linter.lint("inline.sql", sql))
                .containsExactly(new Violation("inline.sql", 3, Rule.RESERVED_WORD, "COMMENT"));
    }

    @Test
    void theReservedWordListCoversTheWordsThePlanNames() {
        assertThat(reservedWords())
                .contains(
                        "USER", "COMMENT", "DATE", "SIZE", "TO", "FROM", "ORDER", "GROUP", "LEVEL", "TYPE", "VALUE",
                        "KEY", "ROWNUM", "ROWID", "SYSDATE", "LIMIT", "OFFSET", "WINDOW", "NUMBER", "UID", "SESSION")
                .doesNotContain("NAME", "STATUS", "ACTIVE", "ACTION", "OUTCOME", "REASON");
    }

    static Stream<Object[]> goodFixtures() throws IOException {
        return fixtures("good");
    }

    static Stream<Object[]> badFixtures() throws IOException {
        return fixtures("bad");
    }

    private static Stream<Object[]> fixtures(String folder) throws IOException {
        Resource[] resources = RESOLVER.getResources("classpath:migration-lint/" + folder + "/*.sql");
        assertThat(resources).as("fixtures in migration-lint/" + folder).isNotEmpty();
        List<Object[]> arguments = new ArrayList<>();
        for (Resource resource : resources) {
            arguments.add(new Object[] {resource.getFilename(), read(resource)});
        }
        return arguments.stream();
    }

    private static Set<String> expectations(String sql) {
        Set<String> expected = new HashSet<>();
        sql.lines()
                .filter(line -> line.startsWith(EXPECT))
                .map(line -> line.substring(EXPECT.length()).trim())
                .forEach(expected::add);
        return expected;
    }

    private static Set<String> reservedWords() {
        try {
            String text = read(RESOLVER.getResource("classpath:migration-lint/reserved-words.txt"));
            Set<String> words = new HashSet<>();
            Arrays.stream(text.split("\n"))
                    .map(String::trim)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .map(line -> line.toUpperCase(Locale.ROOT))
                    .forEach(words::add);
            return words;
        } catch (IOException e) {
            throw new IllegalStateException("Cannot read the reserved-word list", e);
        }
    }

    private static String read(Resource resource) throws IOException {
        try (InputStream in = resource.getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
