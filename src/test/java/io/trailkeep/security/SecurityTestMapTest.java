// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Keeps {@code security/TEST_MAP.md} (WP-28 part f) from rotting: every name it gives as evidence must exist at this
 * commit. Each backticked token of one of these shapes is resolved:
 *
 * <ul>
 *   <li>a test class ({@code FooTest}, {@code FooIT}): a file of that name under {@code src/test/java};
 *   <li>any other class name ({@code CaseScope}): a file of that name under {@code src/main/java} or
 *       {@code src/test/java};
 *   <li>a repository path ({@code security/zap/plan.yaml}), optionally with a workflow job
 *       ({@code .github/workflows/ci.yml#build}): the file or directory, and the job in it;
 *   <li>{@code semgrep:<id>}: a rule with that id in {@code security/semgrep};
 *   <li>{@code zap:<rule id>}: an entry of {@code security/zap/suppressions.yaml}, and every entry there must be on the
 *       page;
 *   <li>{@code enforcer:<rule>}: that rule in {@code pom.xml}.
 * </ul>
 *
 * <p>Other backticked text (configuration keys, header values, markup) is not checked. The page must also keep a
 * section for every control row of BUILD_PLAN §9.
 */
class SecurityTestMapTest {

    private static final Path MAP = Path.of("security/TEST_MAP.md");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]+)`");
    private static final Pattern TEST_CLASS = Pattern.compile("[A-Z][A-Za-z0-9]*(?:Test|IT)");
    private static final Pattern CLASS = Pattern.compile("[A-Z][a-z0-9]+(?:[A-Z][a-z0-9]*)+");
    private static final Pattern PATH = Pattern.compile("([.A-Za-z0-9_-]+(?:/[.A-Za-z0-9_-]+)+)(?:#([a-z0-9-]+))?");
    private static final Pattern RULE_ID = Pattern.compile("(?m)^\\s*-\\s*id:\\s*([\\w-]+)\\s*$");
    private static final Pattern SUPPRESSION = Pattern.compile("(?m)^\\s*-\\s*ruleId:\\s*(\\d+)\\s*$");

    /** The control rows of BUILD_PLAN §9, each a section of the page. */
    private static final List<String> CONTROLS = List.of(
            "Identity",
            "Authorization",
            "Audit",
            "Input validation",
            "Injection",
            "Browser security",
            "Secrets",
            "Transport",
            "Data at rest",
            "Logging",
            "Errors",
            "Availability",
            "Supply chain",
            "Separation of duties",
            "Privacy");

    @Test
    void everyNameTheMapGivesAsEvidenceExists() {
        String map = read(MAP);
        Set<String> sources = javaFiles();
        String pom = read(Path.of("pom.xml"));
        Set<String> rules = semgrepRules();
        Set<String> suppressions = matches(SUPPRESSION, read(Path.of("security/zap/suppressions.yaml")));
        List<String> unresolved = new ArrayList<>();
        Matcher token = BACKTICKED.matcher(map);
        int checked = 0;
        while (token.find()) {
            String name = token.group(1);
            Boolean resolves = resolve(name, sources, pom, rules, suppressions);
            if (resolves != null) {
                checked++;
                if (!resolves) {
                    unresolved.add(name);
                }
            }
        }
        assertThat(checked).as("names checked in " + MAP).isGreaterThan(100);
        assertThat(unresolved).as("names in " + MAP + " that do not exist").isEmpty();
    }

    @Test
    void everyZapSuppressionIsOnTheMap() {
        String map = read(MAP);
        assertThat(matches(SUPPRESSION, read(Path.of("security/zap/suppressions.yaml"))))
                .isNotEmpty()
                .allSatisfy(id -> assertThat(map)
                        .as("security/zap/suppressions.yaml rule " + id + " on the map")
                        .contains("`zap:" + id + "`"));
    }

    @Test
    void everySecurityDesignControlHasItsOwnSection() {
        String map = read(MAP);
        assertThat(CONTROLS).allSatisfy(control -> assertThat(map).containsPattern("(?m)^### " + control + "\\b"));
        assertThat(map).contains("## Gaps", "## Missing documents", "## WP-28 acceptance");
    }

    /**
     * Whether a backticked token resolves.
     *
     * @return true or false for a token of a checked shape, {@code null} for any other text, including a placeholder
     *     such as {@code zap:<rule id>}
     */
    private static @Nullable Boolean resolve(
            String name, Set<String> sources, String pom, Set<String> rules, Set<String> suppressions) {
        if (name.contains("<")) {
            return null;
        }
        if (name.startsWith("semgrep:")) {
            return rules.contains(name.substring("semgrep:".length()));
        }
        if (name.startsWith("zap:")) {
            return suppressions.contains(name.substring("zap:".length()));
        }
        if (name.startsWith("enforcer:")) {
            String rule = name.substring("enforcer:".length());
            return pom.contains("<" + rule + "/>") || pom.contains("<" + rule + ">");
        }
        return resolveByShape(name, sources);
    }

    /** A class name or a repository path; {@code null} for text of neither shape. */
    private static @Nullable Boolean resolveByShape(String name, Set<String> sources) {
        if (TEST_CLASS.matcher(name).matches()) {
            return sources.contains("test:" + name);
        }
        if (CLASS.matcher(name).matches()) {
            return sources.contains("test:" + name) || sources.contains("main:" + name);
        }
        Matcher path = PATH.matcher(name);
        if (path.matches()) {
            Path file = Path.of(path.group(1));
            if (!Files.exists(file)) {
                return false;
            }
            return path.group(2) == null
                    || Pattern.compile("(?m)^  " + Pattern.quote(path.group(2)) + ":\\s*$")
                            .matcher(read(file))
                            .find();
        }
        return null;
    }

    /** Every Java type name in the sources, as {@code main:Name} or {@code test:Name}. */
    private static Set<String> javaFiles() {
        Set<String> names = new TreeSet<>();
        for (String root : List.of("main", "test")) {
            try (Stream<Path> files = Files.walk(Path.of("src", root, "java"))) {
                files.map(Path::getFileName)
                        .map(Path::toString)
                        .filter(file -> file.endsWith(".java"))
                        .forEach(file -> names.add(root + ":" + file.substring(0, file.length() - ".java".length())));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return names;
    }

    private static Set<String> semgrepRules() {
        Set<String> ids = new TreeSet<>();
        try (Stream<Path> files = Files.list(Path.of("security/semgrep"))) {
            files.filter(file -> file.toString().endsWith(".yaml"))
                    .forEach(file -> ids.addAll(matches(RULE_ID, read(file))));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return ids;
    }

    private static Set<String> matches(Pattern pattern, String text) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
