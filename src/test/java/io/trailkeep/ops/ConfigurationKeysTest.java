// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * No profile file sets a key Spring Boot no longer reads. A property whose metadata says it is deprecated at level
 * {@code error} has been removed: Boot binds nothing from it, so a value under that name does nothing, silently. The
 * {@code server.error.*} keys were in that state after the move to Spring Boot 4 ({@code spring.web.error.*} replaced
 * them). The removed keys come from the configuration metadata of every jar on the classpath, so an upgrade that
 * removes another key fails this test without anyone listing it.
 */
class ConfigurationKeysTest {

    private static final Path RESOURCES = Path.of("src/main/resources");

    /** Boot 4.1 reports a few hundred; far fewer means the metadata was not found. */
    private static final int REMOVED_FLOOR = 100;

    @Test
    void noProfileFileSetsKeysThatSpringBootNoLongerReads() throws IOException {
        Map<String, String> removed = removedKeys();
        assertThat(removed)
                .as("removed keys read from the classpath's configuration metadata")
                .hasSizeGreaterThanOrEqualTo(REMOVED_FLOOR)
                .containsEntry("server.error.include-stacktrace", "spring.web.error.include-stacktrace");
        List<Path> files = profileFiles();
        assertThat(files).contains(RESOURCES.resolve("application.yml"), RESOURCES.resolve("application-prod.yml"));
        List<String> wrong = new ArrayList<>();
        for (Path file : files) {
            for (String key : keys(file)) {
                removed.forEach((name, replacement) -> {
                    if (key.equals(name) || key.startsWith(name + ".")) {
                        wrong.add(file.getFileName() + ": " + key + " is no longer read; use " + replacement);
                    }
                });
            }
        }
        assertThat(wrong).isEmpty();
    }

    /** Every property deprecated at level {@code error}, with its replacement ("none" if it has none). */
    private static Map<String, String> removedKeys() throws IOException {
        Map<String, String> removed = new TreeMap<>();
        JsonMapper json = JsonMapper.builder().build();
        Resource[] metadata = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:META-INF/*spring-configuration-metadata.json");
        for (Resource resource : metadata) {
            try (InputStream in = resource.getInputStream()) {
                for (JsonNode property : json.readTree(in).path("properties")) {
                    JsonNode deprecation = property.path("deprecation");
                    if ("error".equals(deprecation.path("level").asString(""))) {
                        removed.put(
                                property.path("name").asString(),
                                deprecation.path("replacement").asString("none"));
                    }
                }
            }
        }
        return removed;
    }

    private static List<Path> profileFiles() throws IOException {
        try (Stream<Path> files = Files.list(RESOURCES)) {
            return files.filter(file -> file.getFileName().toString().matches("application.*\\.ya?ml"))
                    .sorted()
                    .toList();
        }
    }

    /** The full key of every value in every document of a YAML file, as Spring's relaxed form in lower case. */
    private static List<String> keys(Path file) throws IOException {
        List<String> keys = new ArrayList<>();
        try (Reader reader = Files.newBufferedReader(file)) {
            for (Object document : new Yaml(new SafeConstructor(new LoaderOptions())).loadAll(reader)) {
                flatten("", document, keys);
            }
        }
        return keys;
    }

    private static void flatten(String prefix, Object node, List<String> keys) {
        if (node instanceof Map<?, ?> map) {
            map.forEach((name, value) -> {
                String part = String.valueOf(name).replace("[", "").replace("]", "");
                flatten(prefix.isEmpty() ? part : prefix + "." + part, value, keys);
            });
        } else if (!prefix.isEmpty()) {
            keys.add(prefix.toLowerCase(Locale.ROOT));
        }
    }
}
