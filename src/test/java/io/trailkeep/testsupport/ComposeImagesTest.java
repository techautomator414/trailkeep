// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Keeps {@code compose.yaml} on the images the tests run (issue 256). The same file serves local development and the
 * ZAP DAST stack, so an old Keycloak or ClamAV there means the DAST scan, the thing that gates a release, runs against
 * a different identity provider or scanner than the tests exercise. Every service's image must be pinned by digest with
 * its tag kept and, where a test image of the same repository exists under {@code src/test/resources/images/}, must be
 * exactly that image (repository, tag and digest). Dependabot's {@code docker} entry moves the test images and its
 * {@code docker-compose} entry moves this file; this test fails when only one of the two PRs has landed.
 */
class ComposeImagesTest {

    private static final Path COMPOSE = Path.of("compose.yaml");
    private static final Path IMAGES = Path.of("src/test/resources/images");

    private static final Pattern PINNED = Pattern.compile("\\S+:[^@:/\\s]+@sha256:[0-9a-f]{64}");
    private static final Pattern FROM = Pattern.compile("(?m)^FROM\\s+(\\S+)\\s*$");

    /** Services whose image has no test counterpart; they still must be digest-pinned. */
    private static final List<String> COMPOSE_ONLY = List.of("alpine/openssl");

    @Test
    void everyServiceImageIsPinnedByDigestWithItsTagKept() throws IOException {
        Map<String, String> unpinned = new TreeMap<>();
        services().forEach((name, image) -> {
            if (!PINNED.matcher(image).matches()) {
                unpinned.put(name, image);
            }
        });

        assertThat(unpinned)
                .as("compose.yaml images must be repository:tag@sha256:digest")
                .isEmpty();
    }

    @Test
    void everyServiceImageIsTheTestImageOfTheSameRepositoryOrAKnownComposeOnlyOne() throws IOException {
        Map<String, String> testImages = testImagesByRepository();
        Map<String, String> drifted = new TreeMap<>();
        List<String> unknown = new ArrayList<>();
        services().forEach((name, image) -> {
            String repository = repository(image);
            String expected = testImages.get(repository);
            if (expected == null) {
                if (!COMPOSE_ONLY.contains(repository)) {
                    unknown.add(name + ": " + image);
                }
            } else if (!expected.equals(image)) {
                drifted.put(name, image + " (tests run " + expected + ")");
            }
        });

        assertThat(drifted)
                .as("compose.yaml differs from src/test/resources/images/<name>/Dockerfile")
                .isEmpty();
        assertThat(unknown)
                .as("an image with no test counterpart must be listed in COMPOSE_ONLY here, deliberately")
                .isEmpty();
    }

    @Test
    void dependabotsDockerComposeEntryCoversTheComposeFile() throws IOException {
        JsonNode config = new YAMLMapper().readTree(Files.readString(Path.of(".github/dependabot.yml")));
        boolean covered = false;
        for (JsonNode update : config.path("updates")) {
            covered |= "docker-compose".equals(update.path("package-ecosystem").asString())
                    && "/".equals(update.path("directory").asString());
        }
        assertThat(covered)
                .as("a docker-compose entry for / in .github/dependabot.yml")
                .isTrue();
    }

    private static Map<String, String> services() throws IOException {
        Map<String, String> images = new TreeMap<>();
        JsonNode services = new YAMLMapper().readTree(Files.readString(COMPOSE)).path("services");
        services.properties()
                .forEach(service -> images.put(
                        service.getKey(), service.getValue().path("image").asString()));
        assertThat(images).isNotEmpty().doesNotContainValue("");
        return images;
    }

    private static Map<String, String> testImagesByRepository() throws IOException {
        Map<String, String> byRepository = new TreeMap<>();
        try (Stream<Path> dirs = Files.list(IMAGES)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Matcher from = FROM.matcher(Files.readString(dir.resolve("Dockerfile")));
                assertThat(from.find()).as(dir + " has a FROM line").isTrue();
                byRepository.put(repository(from.group(1)), from.group(1));
            }
        }
        return byRepository;
    }

    /** The image name without tag or digest: everything before the first {@code :} after the last {@code /}. */
    private static String repository(String image) {
        String withoutDigest = image.contains("@") ? image.substring(0, image.indexOf('@')) : image;
        int colon = withoutDigest.indexOf(':', withoutDigest.lastIndexOf('/') + 1);
        return colon < 0 ? withoutDigest : withoutDigest.substring(0, colon);
    }
}
