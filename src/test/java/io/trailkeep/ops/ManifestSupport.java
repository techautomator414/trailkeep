// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/** Reads the base manifests under {@code deploy/} for the purge and web manifest tests (#246). */
final class ManifestSupport {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private ManifestSupport() {}

    static List<JsonNode> documents(Path file) throws IOException {
        return YAML.readerFor(JsonNode.class)
                .<JsonNode>readValues(Files.readString(file))
                .readAll();
    }

    static JsonNode document(Path file, String kind, String name) throws IOException {
        return documents(file).stream()
                .filter(d -> kind.equals(d.path("kind").asString())
                        && name.equals(d.at("/metadata/name").asString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(kind + "/" + name + " not found in " + file));
    }

    /** Whether the NetworkPolicy's podSelector (matchLabels and In/NotIn expressions) selects a pod with these labels. */
    static boolean selects(JsonNode policy, Map<String, String> labels) {
        JsonNode selector = policy.at("/spec/podSelector");
        for (Map.Entry<String, JsonNode> expected : selector.path("matchLabels").properties()) {
            if (!expected.getValue().asString().equals(labels.get(expected.getKey()))) {
                return false;
            }
        }
        for (JsonNode expression : selector.path("matchExpressions")) {
            String actual = labels.get(expression.path("key").asString());
            List<String> values = new ArrayList<>();
            expression.path("values").forEach(v -> values.add(v.asString()));
            boolean in = actual != null && values.contains(actual);
            switch (expression.path("operator").asString()) {
                case "In" -> {
                    if (!in) {
                        return false;
                    }
                }
                case "NotIn" -> {
                    if (in) {
                        return false;
                    }
                }
                default -> throw new AssertionError("Unhandled operator in " + expression);
            }
        }
        return true;
    }

    static Map<String, String> labels(JsonNode podTemplate) {
        Map<String, String> labels = new java.util.TreeMap<>();
        podTemplate
                .at("/metadata/labels")
                .properties()
                .forEach(e -> labels.put(e.getKey(), e.getValue().asString()));
        return labels;
    }
}
