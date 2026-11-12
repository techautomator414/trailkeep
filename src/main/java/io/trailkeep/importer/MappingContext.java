// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.FieldTransformer.DstRule;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import javax.crypto.SecretKey;
import tools.jackson.databind.JsonNode;

/**
 * What every row of one extract is evaluated against (WP-26 part k1a, design §6): the validated mapping, its source
 * id, lookups, time zone and daylight-saving rule, {@code import.hash-key}, and the mapping's patterns and
 * formats compiled once.
 *
 * @param mapping the validated mapping
 * @param sourceId {@code source.id}, the prefix of every {@code LEGACY_REF}
 * @param lookups the lookups, inline and from files, by name and legacy value
 * @param zone {@code source.timezone}
 * @param dst {@code source.dstAmbiguity}
 * @param hashKey {@code import.hash-key}
 * @param formats the mapping's compiled patterns and formats
 */
record MappingContext(
        JsonNode mapping,
        String sourceId,
        Map<String, Map<String, String>> lookups,
        ZoneId zone,
        DstRule dst,
        SecretKey hashKey,
        CompiledFormats formats) {

    /**
     * Reads a mapping that {@code validate-mapping} accepted.
     *
     * @param mapping the mapping
     * @param hashKey {@code import.hash-key}
     * @param fileLookups the lookups read from files ({@link LookupFiles}), by name and legacy value
     * @return its context
     */
    static MappingContext of(JsonNode mapping, SecretKey hashKey, Map<String, Map<String, String>> fileLookups) {
        Map<String, Map<String, String>> lookups = new HashMap<>(fileLookups);
        for (Map.Entry<String, JsonNode> lookup : mapping.path("lookups").properties()) {
            Map<String, String> values = new HashMap<>();
            if (!lookup.getValue().has("file")) {
                lookup.getValue()
                        .properties()
                        .forEach(entry ->
                                values.put(entry.getKey(), entry.getValue().asString()));
                lookups.put(lookup.getKey(), Map.copyOf(values));
            }
        }
        return new MappingContext(
                mapping,
                mapping.at("/source/id").asString(),
                Map.copyOf(lookups),
                ZoneId.of(mapping.at("/source/timezone").asString()),
                DstRule.valueOf(
                        mapping.at("/source/dstAmbiguity").asString("reject").toUpperCase(Locale.ROOT)),
                hashKey,
                new CompiledFormats());
    }
}
