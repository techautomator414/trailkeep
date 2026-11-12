// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import com.google.re2j.Pattern;
import com.google.re2j.PatternSyntaxException;
import io.trailkeep.importer.MappingProblem.Code;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import tools.jackson.databind.JsonNode;

/**
 * The regular expressions and declared date formats of one mapping (design §6.3), each compiled once and reused for
 * every row, never compiled per row. {@link #compileAll} compiles every one of them up front, so {@code
 * validate-mapping} reports a bad pattern, format or time zone as a FATAL mapping problem before anything is
 * extracted. Not thread-safe: an extract evaluates one row at a time.
 */
final class CompiledFormats {

    private final Map<String, Pattern> patterns = new HashMap<>();
    private final Map<String, DateTimeFormatter> formats = new HashMap<>();

    /**
     * A {@code regexExtract} pattern or a {@code when.matches} pattern, as RE2 (linear time, no back-references).
     *
     * @param regex the mapping's pattern
     * @return the compiled pattern, the same instance for every row
     */
    Pattern pattern(String regex) {
        return patterns.computeIfAbsent(regex, Pattern::compile);
    }

    /**
     * A declared {@code timestamp} or {@code date} format ({@link DeclaredFormat}).
     *
     * @param format the mapping's format
     * @return the strict formatter, the same instance for every row
     */
    DateTimeFormatter format(String format) {
        return formats.computeIfAbsent(format, DeclaredFormat::of);
    }

    /**
     * Compiles every pattern and format of a mapping, and reads its time zone.
     *
     * @param mapping the whole mapping
     * @return the problems, by place: {@code BAD_PATTERN}, {@code BAD_FORMAT} or {@code BAD_TIMEZONE}
     */
    List<MappingProblem> compileAll(JsonNode mapping) {
        Set<MappingProblem> problems = new TreeSet<>();
        compile(mapping, "", problems);
        try {
            ZoneId.of(mapping.at("/source/timezone").asString(""));
        } catch (DateTimeException unknown) {
            problems.add(new MappingProblem(Code.BAD_TIMEZONE, "/source/timezone"));
        }
        return List.copyOf(problems);
    }

    private void compile(JsonNode node, String pointer, Set<MappingProblem> problems) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                compile(node.get(i), pointer + "/" + i, problems);
            }
        } else if (node.isObject()) {
            compileOne(node, pointer, problems);
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                compile(entry.getValue(), pointer + "/" + MappingValidator.escape(entry.getKey()), problems);
            }
        }
    }

    /**
     * The pattern or format of one transform. A regexExtract's pattern and a when's matches are RE2 patterns; a
     * timestamp's or date's format is a date format. Elsewhere "format" is a comment body's format.
     */
    private void compileOne(JsonNode node, String pointer, Set<MappingProblem> problems) {
        String regex = node.has("regexExtract") ? "pattern" : "matches";
        if (node.path(regex).isString()) {
            try {
                pattern(node.get(regex).asString());
            } catch (PatternSyntaxException bad) {
                problems.add(new MappingProblem(Code.BAD_PATTERN, pointer + "/" + regex));
            }
        }
        if ((node.has("timestamp") || node.has("date")) && node.path("format").isString()) {
            try {
                format(node.get("format").asString());
            } catch (IllegalArgumentException bad) {
                problems.add(new MappingProblem(Code.BAD_FORMAT, pointer + "/format"));
            }
        }
    }
}
