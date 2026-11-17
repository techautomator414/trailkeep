// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testcontainers.utility.DockerImageName;

/**
 * The container images the integration tests start, each named in exactly one place (issue 235): the {@code FROM}
 * line of {@code src/test/resources/images/<name>/Dockerfile}. Dependabot's {@code docker} ecosystem reads those files,
 * so every image is proposed for update, tag and digest together, and the tests read the same line, so the two cannot
 * drift apart. Every line is pinned by digest, with the human-readable tag kept in front of it. No test names an image
 * in Java: {@code ImageSourcesTest} fails the build if a {@code DockerImageName.parse} with a literal appears outside
 * this class.
 *
 * <p>The digest is what Testcontainers pulls ({@code repository@sha256:...}); the tag is documentation and what
 * Dependabot moves.
 */
public final class TestImages {

    /** {@code FROM <repository>[:<tag>]@sha256:<digest>}, nothing after it. */
    private static final Pattern FROM =
            Pattern.compile("(?i)^FROM\\s+(\\S+?)(?::([^@\\s]+))?@(sha256:[0-9a-f]{64})\\s*$");

    private TestImages() {}

    /**
     * The image a Dockerfile under {@code images/<name>} names, by its digest.
     *
     * @param name the directory, such as {@code postgres} or {@code oracle-free}
     * @return the image name as Testcontainers pulls it, {@code repository@sha256:...}
     */
    public static DockerImageName image(String name) {
        return DockerImageName.parse(reference(name));
    }

    /**
     * The same image as a plain reference string.
     *
     * @param name the directory
     * @return {@code repository@sha256:...}
     */
    public static String reference(String name) {
        Matcher from = FROM.matcher(fromLine(name));
        if (!from.matches()) {
            throw new IllegalStateException("images/" + name + "/Dockerfile: its FROM line must be"
                    + " 'FROM <repository>:<tag>@sha256:<64 hex digits>' (pinned by digest, tag kept)");
        }
        return from.group(1) + "@" + from.group(3);
    }

    /**
     * The tag the file keeps next to the digest, for logs and documentation.
     *
     * @param name the directory
     * @return the tag, or an empty string if the file has none
     */
    public static String tag(String name) {
        Matcher from = FROM.matcher(fromLine(name));
        return from.matches() && from.group(2) != null ? from.group(2) : "";
    }

    private static String fromLine(String name) {
        String resource = "/images/" + name + "/Dockerfile";
        try (InputStream in = TestImages.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("No test image file " + resource + " on the classpath");
            }
            List<String> froms = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .filter(line -> line.regionMatches(true, 0, "FROM ", 0, 5))
                    .toList();
            if (froms.size() != 1) {
                throw new IllegalStateException(resource + " must hold exactly one FROM line, found " + froms.size());
            }
            return froms.get(0).strip();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
