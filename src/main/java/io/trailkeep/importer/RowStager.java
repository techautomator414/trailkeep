// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.FieldTransformer.Context;
import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.ImportIssue.Severity;
import io.trailkeep.importer.staging.RunCheckpoint.StagedEntity;
import io.trailkeep.importer.staging.StagedAttachment;
import io.trailkeep.importer.staging.StagedCase;
import io.trailkeep.importer.staging.StagedComment;
import io.trailkeep.importer.staging.StagedEvent;
import io.trailkeep.importer.staging.StagedPerson;
import io.trailkeep.importer.staging.StagedRow;
import io.trailkeep.importer.staging.StagedValues;
import io.trailkeep.importer.staging.StagingKey;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Turns one source row into one staging row (WP-26 part k1a, design §5, §6): its canonical key, for a comment,
 * attachment or event the canonical key of its case, every mapped field through the closed transform set, typed for
 * its staging column, and the keyed hash of the canonical row. Pure, like the transforms: a row stages the same way
 * whichever source it came from. A row whose key cannot be built is not staged; a field that fails is staged empty.
 * Both leave issue codes, never values (§10). An attachment's file, when one is given, is hashed in place before the
 * row hash (26k2, {@link AttachmentFiles}), and its inline bytes are written to the content area (26l2), so a changed
 * file changes the row.
 */
final class RowStager {

    /** {@code LEGACY_REF VARCHAR(100)} holds {@code <source id>:<canonical key>} (§5 "Length budget"). */
    static final int LEGACY_REF_LENGTH = 100;

    private enum Type {
        TEXT,
        PERSON_KEY,
        INSTANT,
        DATE,
        FLAG,
        CONTENT
    }

    /** The fields staged as something other than text; field names are unique across the entities. */
    private static final Map<String, Type> TYPES = Map.ofEntries(
            Map.entry("openedAt", Type.INSTANT),
            Map.entry("closedAt", Type.INSTANT),
            Map.entry("writtenAt", Type.INSTANT),
            Map.entry("uploadedAt", Type.INSTANT),
            Map.entry("at", Type.INSTANT),
            Map.entry("identifiedOn", Type.DATE),
            Map.entry("legacyAcl", Type.FLAG),
            Map.entry("legalHold", Type.FLAG),
            Map.entry("ownerKey", Type.PERSON_KEY),
            Map.entry("authorKey", Type.PERSON_KEY),
            Map.entry("uploaderKey", Type.PERSON_KEY),
            Map.entry("whoKey", Type.PERSON_KEY),
            Map.entry("content", Type.CONTENT));

    /**
     * One row, staged or not.
     *
     * @param key its canonical key, or {@code null} when it could not be built (then nothing is staged)
     * @param keyHash the keyed hash of the key, for its issues; {@code null} without a key
     * @param caseKey a child's case key; {@code null} for a person or a case
     * @param values its typed fields
     * @param rowHash the keyed hash of the canonical row
     * @param issues what went wrong, as codes
     */
    record Staged(
            @Nullable String key,
            @Nullable String keyHash,
            @Nullable String caseKey,
            Map<String, @Nullable Object> values,
            String rowHash,
            List<Issue> issues) {}

    private final MappingContext context;
    private final StagedEntity entity;
    private final JsonNode spec;
    private final @Nullable AttachmentFiles files;

    /**
     * A stager for one entity of a mapping.
     *
     * @param context the mapping
     * @param entity the entity
     */
    RowStager(MappingContext context, StagedEntity entity) {
        this(context, entity, null);
    }

    /**
     * A stager for one entity of a mapping that hashes attachment files.
     *
     * @param context the mapping
     * @param entity the entity
     * @param files the attachment files, for the attachment entity; {@code null} for the others
     */
    RowStager(MappingContext context, StagedEntity entity, @Nullable AttachmentFiles files) {
        this.context = context;
        this.entity = entity;
        this.files = files;
        this.spec = entity == StagedEntity.PERSON
                ? context.mapping().path("people")
                : context.mapping().path("entities").path(entity.name().toLowerCase(Locale.ROOT));
    }

    /**
     * Stages one row.
     *
     * @param row the source row, by column name in lower case
     * @return the staged values and their issues
     */
    Staged stage(Map<String, @Nullable Object> row) {
        List<Issue> issues = new ArrayList<>();
        String key = key(row, spec.path("key"), insensitive(spec), "KEY_INVALID", issues);
        String keyHash = key == null ? null : KeyedHash.hex(context.hashKey(), key);
        String caseKey = null;
        if (key != null && spec.has("parent")) {
            caseKey = key(
                    row,
                    spec.at("/parent/columns"),
                    insensitive(context.mapping().at("/entities/case")),
                    "PARENT_KEY_INVALID",
                    issues);
            key = caseKey == null ? null : key;
        }
        Map<String, @Nullable Object> values = new HashMap<>();
        if (key != null) {
            Context fields = new Context(
                    row, context.lookups(), context.zone(), context.dst(), context.hashKey(), context.formats());
            for (Map.Entry<String, JsonNode> field : spec.path("fields").properties()) {
                values.put(field.getKey(), typed(field.getKey(), field.getValue(), fields, issues));
            }
            content(values, fields, issues);
            if (entity == StagedEntity.COMMENT) {
                values.put("bodyFormat", spec.at("/fields/body/format").asString("plain"));
            }
        }
        String rowHash = key == null ? "" : rowHash(key, caseKey, values);
        return new Staged(key, keyHash, caseKey, values, rowHash, List.copyOf(issues));
    }

    /** An attachment's declared size, then its file's size and SHA-256 when its file is read here. */
    private void content(Map<String, @Nullable Object> values, Context fields, List<Issue> issues) {
        JsonNode size = spec.at("/fields/content/size");
        if (size.isString()) {
            values.put("size", size(object("number", size), fields, issues));
        }
        if (files != null) {
            files.capture(fields.row(), values, issues);
        }
    }

    /**
     * The staging row of a staged row.
     *
     * @param runId the run
     * @param staged a row with a key
     * @return its entity
     */
    StagedRow row(long runId, Staged staged) {
        StagingKey key = new StagingKey(runId, Objects.requireNonNull(staged.key(), "a staged row has a key"));
        StagedValues values = new StagedValues(staged.values());
        String caseKey = Objects.requireNonNullElse(staged.caseKey(), "");
        return switch (entity) {
            case PERSON -> new StagedPerson(key, staged.rowHash(), values);
            case CASE -> new StagedCase(key, staged.rowHash(), values);
            case COMMENT -> new StagedComment(key, staged.rowHash(), caseKey, values);
            case ATTACHMENT -> new StagedAttachment(key, staged.rowHash(), caseKey, values);
            case EVENT -> new StagedEvent(key, staged.rowHash(), caseKey, values);
        };
    }

    private @Nullable String key(
            Map<String, @Nullable Object> row, JsonNode columns, boolean insensitive, String code, List<Issue> issues) {
        List<@Nullable Object> components = new ArrayList<>();
        int count = columns.isArray() ? columns.size() : 1;
        for (int i = 0; i < count; i++) {
            String column =
                    (columns.isArray() ? columns.get(i) : columns).asString().toLowerCase(Locale.ROOT);
            if (!row.containsKey(column)) {
                issues.add(new Issue("COLUMN_MISSING", Severity.FATAL));
                return null;
            }
            components.add(row.get(column));
        }
        String key = CanonicalKey.of(components, insensitive);
        if (key == null) {
            issues.add(new Issue(code, Severity.ERROR));
        } else if (context.sourceId().length() + 1 + key.length() > LEGACY_REF_LENGTH) {
            issues.add(new Issue("KEY_TOO_LONG", Severity.ERROR));
            return null;
        }
        return key;
    }

    private @Nullable Object typed(String name, JsonNode field, Context fields, List<Issue> issues) {
        Type type = TYPES.getOrDefault(name, Type.TEXT);
        Object value = evaluate(
                switch (type) {
                    case INSTANT -> object("timestamp", field);
                    case DATE -> object("date", field);
                    default -> field;
                },
                fields,
                issues);
        return switch (type) {
            case INSTANT, DATE -> value;
            case FLAG -> flag(value, issues);
            case PERSON_KEY ->
                value == null
                        ? null
                        : CanonicalKey.of(
                                Collections.singletonList(value),
                                insensitive(context.mapping().path("people")));
            // Inline bytes go to the content area when this stager has one; else, like any other bytes, they are
            // refused.
            case CONTENT -> value instanceof byte[] && files != null ? value : text(value, issues);
            case TEXT -> text(value, issues);
        };
    }

    /** A field as the transform it needs: a bare column or another transform is wrapped, one already named is kept. */
    private static JsonNode object(String transform, JsonNode field) {
        return field.isObject() && field.has(transform)
                ? field
                : JsonNodeFactory.instance.objectNode().set(transform, field);
    }

    private static @Nullable Object evaluate(JsonNode field, Context fields, List<Issue> issues) {
        FieldTransformer.Result result = FieldTransformer.evaluate(field, fields);
        issues.addAll(result.issues());
        return result.value();
    }

    private static @Nullable Long size(JsonNode field, Context fields, List<Issue> issues) {
        Object value = evaluate(field, fields, issues);
        try {
            return value == null ? null : ((BigDecimal) value).longValueExact();
        } catch (ArithmeticException fractional) {
            issues.add(new Issue("BAD_NUMBER", Severity.ERROR));
            return null;
        }
    }

    private static @Nullable Boolean flag(@Nullable Object value, List<Issue> issues) {
        if (value == null || value instanceof Boolean) {
            return (Boolean) value;
        }
        return switch (value.toString().strip()) {
            case "true", "True", "TRUE" -> Boolean.TRUE;
            case "false", "False", "FALSE" -> Boolean.FALSE;
            default -> {
                issues.add(new Issue("BAD_FLAG", Severity.ERROR));
                yield null;
            }
        };
    }

    private static @Nullable String text(@Nullable Object value, List<Issue> issues) {
        return switch (value) {
            case null -> null;
            case BigDecimal number -> number.toPlainString();
            // Bytes are content; a text column never holds them (the content area does: content queries, inline bytes).
            case byte[] bytes -> {
                issues.add(new Issue("BINARY_NOT_TEXT", Severity.ERROR));
                yield null;
            }
            default -> value.toString();
        };
    }

    private static boolean insensitive(JsonNode entity) {
        return "insensitive".equals(entity.path("keyCase").asString(""));
    }

    /**
     * HMAC of the entity, key, case key and every field in name order. Values are already typed (text, ISO instants and
     * days, flags, whole numbers), so each has one text form.
     */
    private String rowHash(String key, @Nullable String caseKey, Map<String, @Nullable Object> values) {
        StringBuilder canonical = new StringBuilder(entity.name())
                .append('\u001e')
                .append(key)
                .append('\u001e')
                .append(Objects.toString(caseKey, ""));
        new TreeMap<>(values)
                .forEach((name, value) -> canonical
                        .append('\u001e')
                        .append(name)
                        .append('\u001f')
                        .append(value == null ? "\u0000" : value.toString()));
        return KeyedHash.hex(context.hashKey(), canonical.toString());
    }
}
