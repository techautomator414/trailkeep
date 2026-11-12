// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import io.trailkeep.importer.MappingProblem.Code;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The structural half of {@code validate-mapping} (WP-26 part b, design §3 step 1, §6, §10): the mapping file is read
 * as YAML, checked against {@code importer/mapping-v1.schema.json}, then checked for what a schema cannot say:
 * placeholders only where and as allowed, no credentials, file paths contained in the mapping's directory, child keys
 * that fit the case key, lookups that exist, lengths that fit Trailkeep, only skippable codes in {@code acceptSkip},
 * and patterns, formats and a time zone that compile (26k1a). Lookup files, queries and the test fetch come with later
 * rows (design §12). Problems name a code and a place, never a value.
 */
@Component
final class MappingValidator {

    /** A mapping file is configuration: anything larger is refused before it is parsed. */
    static final int MAX_BYTES = 1024 * 1024;

    /** The only placeholders: whole {@code import.source.*} names, resolved later in url and user only (§10). */
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{import\\.source\\.[a-z][a-z0-9.-]*}");

    private static final Set<String> PLACEHOLDER_FIELDS = Set.of("/source/url", "/source/user");

    private static final Pattern CREDENTIAL =
            Pattern.compile("(?i)(password|passwd|pwd|secret|token|apikey|api_key)\\s*[=:]");

    /** The ERROR codes a mapping may accept as "skipped by rule" (§4.3); none of them loses a case. */
    static final Set<String> SKIPPABLE = Set.of("ATTACHMENT_TOO_LARGE", "ATTACHMENT_MISSING");

    /** Character caps of the Trailkeep columns the length-limited fields are written to. */
    private static final Map<String, Integer> TARGET_LENGTH = Map.of(
            "/entities/case/fields/title", 200,
            "/entities/case/fields/description", 20_000,
            "/entities/case/fields/subject", 1000,
            "/entities/case/fields/legacyNumber", 100,
            "/entities/comment/fields/body", 20_000,
            "/entities/attachment/fields/fileName", 255);

    private final Schema schema;
    private final YAMLMapper yaml = YAMLMapper.builder().build();

    MappingValidator() {
        try (InputStream in = new ClassPathResource("importer/mapping-v1.schema.json").getInputStream()) {
            schema = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
                    .getSchema(in);
        } catch (IOException e) {
            throw new UncheckedIOException("The mapping schema is missing", e);
        }
    }

    /**
     * Checks a mapping file's structure.
     *
     * @param file the mapping file; relative paths inside it resolve against its directory
     * @param content the file's bytes
     * @return the problems, by place; empty when the structure is sound
     */
    List<MappingProblem> validate(Path file, byte[] content) {
        if (content.length > MAX_BYTES) {
            return List.of(new MappingProblem(Code.MAPPING_TOO_LARGE, ""));
        }
        JsonNode root;
        try {
            root = yaml.readTree(content);
        } catch (JacksonException unreadable) {
            // Parser messages quote the file; only the fact is kept (§10).
            return List.of(new MappingProblem(Code.MAPPING_UNREADABLE, ""));
        }
        if (root == null || !root.isObject()) {
            return List.of(new MappingProblem(Code.MAPPING_UNREADABLE, ""));
        }
        Set<MappingProblem> problems = new TreeSet<>();
        for (Error error : schema.validate(root)) {
            problems.add(new MappingProblem(
                    Code.MAPPING_SCHEMA, error.getInstanceLocation().toString()));
        }
        scanText(root, "", problems);
        if (problems.isEmpty()) {
            Path absolute = file.toAbsolutePath().normalize();
            checkPaths(Objects.requireNonNullElse(absolute.getParent(), absolute), root, problems);
            checkKeys(root, problems);
            checkFields(root.path("entities"), "/entities", root.path("lookups"), problems);
            problems.addAll(new CompiledFormats().compileAll(root));
            checkQueries(root, problems);
            for (JsonNode code : root.path("limits").path("acceptSkip")) {
                if (!SKIPPABLE.contains(code.asString())) {
                    problems.add(new MappingProblem(Code.ACCEPT_SKIP_NOT_ALLOWED, "/limits/acceptSkip"));
                }
            }
        }
        return List.copyOf(problems);
    }

    private static void scanText(JsonNode node, String pointer, Set<MappingProblem> problems) {
        if (node.isString()) {
            String text = node.asString();
            String rest = PLACEHOLDER_FIELDS.contains(pointer)
                    ? PLACEHOLDER.matcher(text).replaceAll("")
                    : text;
            if (rest.contains("${")) {
                problems.add(new MappingProblem(Code.PLACEHOLDER_NOT_ALLOWED, pointer));
            }
            if (CREDENTIAL.matcher(text).find()) {
                problems.add(new MappingProblem(Code.CREDENTIAL_IN_MAPPING, pointer));
            }
            return;
        }
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                scanText(node.get(i), pointer + "/" + i, problems);
            }
        } else if (node.isObject()) {
            for (Map.Entry<String, JsonNode> entry : node.properties()) {
                scanText(entry.getValue(), pointer + "/" + escape(entry.getKey()), problems);
            }
        }
    }

    private static void checkPaths(Path root, JsonNode mapping, Set<MappingProblem> problems) {
        List<String> pointers = new ArrayList<>(List.of("/source/manifest", "/people/file", "/defaults/owner/byTeam"));
        mapping.path("lookups").propertyNames().forEach(name -> pointers.add("/lookups/" + escape(name) + "/file"));
        mapping.path("entities").propertyNames().forEach(name -> pointers.add("/entities/" + name + "/file"));
        for (String pointer : pointers) {
            JsonNode value = mapping.at(pointer);
            if (value.isString() && !contained(root, value.asString())) {
                problems.add(new MappingProblem(Code.PATH_ESCAPES_ROOT, pointer));
            }
        }
    }

    private static boolean contained(Path root, String relative) {
        try {
            Path path = Path.of(relative);
            return !path.isAbsolute() && root.resolve(path).normalize().startsWith(root);
        } catch (InvalidPathException malformed) {
            return false;
        }
    }

    /** Every source query of a JDBC mapping, through {@link SourceQueryValidator} for the source's vendor (§4.1). */
    private static void checkQueries(JsonNode mapping, Set<MappingProblem> problems) {
        SourceQueryValidator.Vendor vendor = vendor(mapping);
        if (vendor == null) {
            return;
        }
        for (String pointer : queryPointers(mapping)) {
            for (MappingProblem.Code code : SourceQueryValidator.validate(
                            mapping.at(pointer).asString(), vendor)
                    .problems()) {
                problems.add(new MappingProblem(code, pointer));
            }
        }
    }

    /**
     * A JDBC mapping's source vendor.
     *
     * @param mapping the parsed mapping
     * @return the vendor, or {@code null} for a file source
     */
    static SourceQueryValidator.@Nullable Vendor vendor(JsonNode mapping) {
        if (!"jdbc".equals(mapping.at("/source/kind").asString(""))) {
            return null;
        }
        // The schema allows exactly these three names for a jdbc source.
        return switch (mapping.at("/source/vendor").asString("")) {
            case "oracle" -> SourceQueryValidator.Vendor.ORACLE;
            case "postgresql" -> SourceQueryValidator.Vendor.POSTGRESQL;
            default -> SourceQueryValidator.Vendor.SQLSERVER;
        };
    }

    /**
     * Where a mapping holds source queries: the people, content, entity and count queries that are present.
     *
     * @param mapping the parsed mapping
     * @return their JSON pointers, each to a string
     */
    static List<String> queryPointers(JsonNode mapping) {
        List<String> pointers = new ArrayList<>(List.of("/people/query", "/entities/attachment/fields/content/query"));
        mapping.path("entities").propertyNames().forEach(name -> {
            pointers.add("/entities/" + name + "/query");
            pointers.add("/entities/" + name + "/countQuery");
        });
        return pointers.stream()
                .filter(pointer -> mapping.at(pointer).isString())
                .toList();
    }

    private static void checkKeys(JsonNode mapping, Set<MappingProblem> problems) {
        JsonNode caseKey = mapping.at("/entities/case/key");
        int arity = caseKey.isArray() ? caseKey.size() : 1;
        for (String child : List.of("comment", "attachment", "event")) {
            JsonNode columns = mapping.at("/entities/" + child + "/parent/columns");
            if (!columns.isMissingNode() && columns.size() != arity) {
                problems.add(new MappingProblem(Code.KEY_ARITY_MISMATCH, "/entities/" + child + "/parent/columns"));
            }
        }
    }

    private static void checkFields(JsonNode node, String pointer, JsonNode lookups, Set<MappingProblem> problems) {
        if (node.isArray()) {
            for (int i = 0; i < node.size(); i++) {
                checkFields(node.get(i), pointer + "/" + i, lookups, problems);
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode lookup = node.path("lookup");
        if (lookup.isString() && !lookups.has(lookup.asString())) {
            problems.add(new MappingProblem(Code.UNKNOWN_LOOKUP, pointer + "/lookup"));
        }
        Integer cap = TARGET_LENGTH.get(pointer);
        if (cap != null && node.path("maxLength").asInt(0) > cap) {
            problems.add(new MappingProblem(Code.MAX_LENGTH_OVER_TARGET, pointer + "/maxLength"));
        }
        for (Map.Entry<String, JsonNode> entry : node.properties()) {
            checkFields(entry.getValue(), pointer + "/" + escape(entry.getKey()), lookups, problems);
        }
    }

    /** JSON pointer escaping (RFC 6901). */
    static String escape(String key) {
        return key.replace("~", "~0").replace("/", "~1");
    }
}
