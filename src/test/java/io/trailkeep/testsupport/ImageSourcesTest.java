// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Keeps every test container image in one place Dependabot reads (issue 235). A new image cannot bypass the files: a
 * string literal handed to {@code DockerImageName.parse} or to a container constructor anywhere in the tests fails
 * here. Each image file must parse (one {@code FROM}, tag kept, digest pinned), every name the tests ask for must have
 * a file, and Dependabot's {@code docker} entry must cover the directory that holds them all.
 */
class ImageSourcesTest {

    private static final Path TESTS = Path.of("src/test/java");
    private static final Path IMAGES = Path.of("src/test/resources/images");

    /** An image named by a string literal, which is what must not appear outside the image files. */
    private static final Pattern LITERAL = Pattern.compile(
            "(DockerImageName\\s*\\.\\s*parse|new\\s+(?:GenericContainer|PostgreSQLContainer|MSSQLServerContainer"
                    + "|OracleContainer|MailpitContainer)\\s*(?:<[^>]*>)?)\\s*\\(\\s*\"");

    private static final Pattern NAMED =
            Pattern.compile("TestImages\\s*\\.\\s*(?:image|reference|tag)\\(\\s*\"([^\"]+)\"");

    @Test
    void noTestNamesAnImageInJava() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaTests()) {
            if (file.getFileName().toString().equals("ImageSourcesTest.java")) {
                continue; // this file spells the patterns it forbids
            }
            Matcher literal = LITERAL.matcher(Files.readString(file));
            while (literal.find()) {
                offenders.add(file + ": " + literal.group().replaceAll("\\s+", " "));
            }
        }
        assertThat(offenders)
                .as("images named in Java: put each in src/test/resources/images/<name>/Dockerfile and read it through"
                        + " TestImages, so Dependabot proposes its updates")
                .isEmpty();
    }

    @Test
    void everyImageFileIsPinnedByDigestWithItsTagKept() throws IOException {
        List<String> names = imageNames();
        assertThat(names)
                .contains(
                        "postgres",
                        "oracle-xe",
                        "oracle-free",
                        "mssql",
                        "clamav",
                        "ghostunnel",
                        "curl",
                        "mailpit",
                        "keycloak",
                        "vnu");
        for (String name : names) {
            assertThat(TestImages.reference(name)).as(name).matches("[^@\\s]+@sha256:[0-9a-f]{64}");
            assertThat(TestImages.tag(name))
                    .as("the tag kept next to the digest of " + name)
                    .isNotEmpty();
        }
    }

    @Test
    void everyRequestedNameHasItsFile() throws IOException {
        List<String> names = imageNames();
        List<String> missing = new ArrayList<>();
        for (Path file : javaTests()) {
            Matcher named = NAMED.matcher(Files.readString(file));
            while (named.find()) {
                if (!names.contains(named.group(1))) {
                    missing.add(file + ": " + named.group(1));
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void dependabotsDockerEntryCoversTheImageDirectories() throws IOException {
        String config = Files.readString(Path.of(".github/dependabot.yml"));
        assertThat(config).contains("package-ecosystem: docker").contains("/src/test/resources/images/*");
    }

    private static List<String> imageNames() throws IOException {
        try (Stream<Path> dirs = Files.list(IMAGES)) {
            return dirs.filter(Files::isDirectory)
                    .filter(dir -> Files.exists(dir.resolve("Dockerfile")))
                    .map(dir -> dir.getFileName().toString())
                    .sorted()
                    .toList();
        }
    }

    private static List<Path> javaTests() throws IOException {
        try (Stream<Path> files = Files.walk(TESTS)) {
            return files.filter(file -> file.toString().endsWith(".java")).toList();
        }
    }
}
