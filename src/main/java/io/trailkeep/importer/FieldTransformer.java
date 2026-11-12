// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import com.google.re2j.Matcher;
import com.google.re2j.Pattern;
import io.trailkeep.importer.ImportIssue.Severity;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import javax.crypto.SecretKey;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

/**
 * The closed transform set of design §6.3, evaluated over one source row (WP-26 part i). Pure: the same field spec,
 * row, lookups, zone and key always give the same value and issues, whether the row came from a database or a file.
 * Every string read from the row is normalised first ({@link LegacyText#normalise}). A problem with a value is an
 * issue code with a severity, never a message holding the value (§10).
 */
final class FieldTransformer {

    /** How a zone-less time inside a daylight-saving overlap or gap is placed ({@code source.dstAmbiguity}). */
    enum DstRule {
        EARLIER,
        LATER,
        REJECT
    }

    /** One problem with a field value. */
    record Issue(String code, Severity severity) {}

    /** A field's value and what went wrong on the way; the value is {@code null} after an ERROR. */
    record Result(@Nullable Object value, List<Issue> issues) {

        boolean failed() {
            return issues.stream().anyMatch(issue -> issue.severity() != Severity.WARNING);
        }
    }

    /**
     * What a field is evaluated against.
     *
     * @param row the source row, by column name in lower case
     * @param lookups the mapping's value tables by name, each by legacy value
     * @param zone {@code source.timezone}
     * @param dst {@code source.dstAmbiguity}
     * @param hashKey {@code import.hash-key} as an HMAC-SHA-256 key, for {@code hash}
     * @param formats the mapping's patterns and formats, compiled once for all its rows
     */
    record Context(
            Map<String, @Nullable Object> row,
            Map<String, Map<String, String>> lookups,
            ZoneId zone,
            DstRule dst,
            SecretKey hashKey,
            CompiledFormats formats) {}

    /** A string operand that looks like this is a column; anything else is a literal. */
    private static final java.util.regex.Pattern COLUMN = java.util.regex.Pattern.compile("[A-Za-z_][A-Za-z0-9_$#]*");

    private final Context context;
    private final List<Issue> issues = new ArrayList<>();

    private FieldTransformer(Context context) {
        this.context = context;
    }

    /**
     * Evaluates one field spec of the mapping: a column name, or an object of transforms (§6.1, §6.3).
     *
     * @param spec the field spec
     * @param context the row and what the transforms need
     * @return the value and its issues
     */
    static Result evaluate(JsonNode spec, Context context) {
        FieldTransformer transformer = new FieldTransformer(context);
        Object value = transformer.field(spec);
        boolean failed = transformer.issues.stream().anyMatch(issue -> issue.severity() != Severity.WARNING);
        return new Result(failed ? null : value, List.copyOf(transformer.issues));
    }

    private @Nullable Object field(JsonNode spec) {
        if (spec.isString()) {
            return column(spec.asString());
        }
        Object value = transform(spec);
        if (value instanceof String text
                && spec.has("maxLength")
                && text.length() > spec.get("maxLength").asInt()) {
            switch (spec.path("onOverflow").asString("fail")) {
                case "truncateWithIssue" -> {
                    issue("VALUE_TRUNCATED", Severity.WARNING);
                    return text.substring(0, spec.get("maxLength").asInt());
                }
                case "continueInComment" -> issue("DESCRIPTION_CONTINUED", Severity.WARNING);
                default -> issue("VALUE_TOO_LONG", Severity.ERROR);
            }
        }
        return value;
    }

    /** The primary transform of a spec, by its key; the first key present wins, in this order. */
    private Map<String, Function<JsonNode, @Nullable Object>> transforms() {
        Map<String, Function<JsonNode, @Nullable Object>> byKey = new LinkedHashMap<>();
        byKey.put("const", spec -> literal(spec.get("const")));
        byKey.put(
                "binary",
                spec -> binary(
                        text(column(spec.path("column").asString(""))),
                        spec.get("binary").asString()));
        byKey.put("column", spec -> column(spec.get("column").asString()));
        byKey.put("path", spec -> column(spec.get("path").asString()));
        byKey.put("contentId", spec -> column(spec.get("contentId").asString()));
        byKey.put("trim", spec -> map(operand(spec.get("trim")), String::strip));
        byKey.put("upper", spec -> map(operand(spec.get("upper")), text -> text.toUpperCase(Locale.ROOT)));
        byKey.put("lower", spec -> map(operand(spec.get("lower")), text -> text.toLowerCase(Locale.ROOT)));
        byKey.put("coalesce", this::coalesce);
        byKey.put("concat", this::concat);
        byKey.put("lookup", this::lookup);
        byKey.put("when", spec -> literal(matches(spec.get("when")) ? spec.path("then") : spec.path("else")));
        byKey.put("regexExtract", this::regexExtract);
        byKey.put(
                "timestamp",
                spec -> timestamp(
                        operand(spec.get("timestamp")), spec.path("format").asString(null)));
        byKey.put(
                "date",
                spec -> date(operand(spec.get("date")), spec.path("format").asString(null)));
        byKey.put("number", spec -> number(operand(spec.get("number"))));
        byKey.put("text", spec -> text(operand(spec.get("text"))));
        byKey.put("hash", spec -> map(operand(spec.get("hash")), this::hmac));
        return byKey;
    }

    private @Nullable Object transform(JsonNode spec) {
        for (Map.Entry<String, Function<JsonNode, @Nullable Object>> entry :
                transforms().entrySet()) {
            if (spec.has(entry.getKey())) {
                return entry.getValue().apply(spec);
            }
        }
        // A content query (attachments) is run by the source, not here.
        return null;
    }

    private @Nullable Object coalesce(JsonNode spec) {
        for (JsonNode candidate : spec.get("coalesce")) {
            Object value = operand(candidate);
            if (value != null && !(value instanceof String text && text.isBlank())) {
                return value;
            }
        }
        return null;
    }

    private String concat(JsonNode spec) {
        List<String> parts = new ArrayList<>();
        for (JsonNode part : spec.get("concat")) {
            String text = text(operand(part));
            if (text != null) {
                parts.add(text);
            }
        }
        return String.join(spec.path("separator").asString(""), parts);
    }

    private static @Nullable String map(@Nullable Object value, Function<String, String> function) {
        String text = text(value);
        return text == null ? null : function.apply(text);
    }

    private @Nullable Object operand(JsonNode node) {
        if (node.isString()) {
            String text = node.asString();
            return COLUMN.matcher(text).matches() ? column(text) : text;
        }
        return node.isObject() ? field(node) : literal(node);
    }

    private @Nullable Object column(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!context.row().containsKey(key)) {
            issue("COLUMN_MISSING", Severity.FATAL);
            return null;
        }
        Object value = context.row().get(key);
        if (value instanceof String text) {
            LegacyText.Normalised normalised = LegacyText.normalise(text);
            if (normalised.changed()) {
                issue("TEXT_NORMALISED", Severity.WARNING);
            }
            return normalised.value();
        }
        return value;
    }

    private @Nullable Object lookup(JsonNode spec) {
        String key = text(operand(spec.path("from")));
        Map<String, String> table =
                context.lookups().getOrDefault(spec.get("lookup").asString(), Map.of());
        if (key != null && table.containsKey(key)) {
            return table.get(key);
        }
        switch (spec.path("onMissing").asString("fail")) {
            case "default" -> {
                return literal(spec.path("default"));
            }
            case "issue" -> issue("LOOKUP_MISSING", Severity.WARNING);
            default -> issue("LOOKUP_MISSING", Severity.ERROR);
        }
        return null;
    }

    private boolean matches(JsonNode when) {
        String value = text(column(when.get("column").asString()));
        if (when.has("notBlank")) {
            return (value != null && !value.isBlank()) == when.get("notBlank").asBoolean();
        }
        if (value == null) {
            return false;
        }
        if (when.has("equals")) {
            return value.equals(when.get("equals").asString());
        }
        if (when.has("in")) {
            for (JsonNode candidate : when.get("in")) {
                if (value.equals(candidate.asString())) {
                    return true;
                }
            }
            return false;
        }
        return context.formats()
                .pattern(when.path("matches").asString(""))
                .matcher(value)
                .find();
    }

    private @Nullable String regexExtract(JsonNode spec) {
        String text = text(operand(spec.get("regexExtract")));
        if (text == null) {
            return null;
        }
        Pattern pattern = context.formats().pattern(spec.path("pattern").asString(""));
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            issue("REGEX_NO_MATCH", Severity.WARNING);
            return null;
        }
        return matcher.group(spec.path("group").asInt(pattern.groupCount() > 0 ? 1 : 0));
    }

    private @Nullable Instant timestamp(@Nullable Object value, @Nullable String format) {
        Object parsed = value instanceof String text ? parse(text, format) : value;
        if (parsed == null && value != null) {
            issue("BAD_TIMESTAMP", Severity.ERROR);
            return null;
        }
        return switch (parsed) {
            case null -> null;
            case Instant instant -> instant;
            case OffsetDateTime offset -> offset.toInstant();
            case LocalDateTime local -> place(local);
            case LocalDate day -> place(day.atStartOfDay());
            default -> {
                issue("BAD_TIMESTAMP", Severity.ERROR);
                yield null;
            }
        };
    }

    private @Nullable LocalDate date(@Nullable Object value, @Nullable String format) {
        Object parsed = value instanceof String text ? parse(text, format) : value;
        if (parsed == null && value != null) {
            issue("BAD_DATE", Severity.ERROR);
            return null;
        }
        return switch (parsed) {
            case null -> null;
            case LocalDate day -> day;
            case LocalDateTime local -> local.toLocalDate();
            case Instant instant -> LocalDate.ofInstant(instant, context.zone());
            case OffsetDateTime offset -> LocalDate.ofInstant(offset.toInstant(), context.zone());
            default -> {
                issue("BAD_DATE", Severity.ERROR);
                yield null;
            }
        };
    }

    /** A text time: with an offset, without one, or a bare day, by the declared format or ISO-8601. */
    private @Nullable Object parse(String text, @Nullable String format) {
        DateTimeFormatter formatter = format == null
                ? DateTimeFormatter.ISO_DATE_TIME
                : context.formats().format(format);
        try {
            TemporalAccessor parsed =
                    formatter.parseBest(text.strip(), OffsetDateTime::from, LocalDateTime::from, LocalDate::from);
            return parsed;
        } catch (DateTimeParseException first) {
            try {
                return format == null ? LocalDate.parse(text.strip()) : null;
            } catch (DateTimeParseException second) {
                return null;
            }
        }
    }

    /** A zone-less time placed in {@code source.timezone}, following {@code dstAmbiguity} in an overlap or a gap. */
    private @Nullable Instant place(LocalDateTime local) {
        List<ZoneOffset> offsets = context.zone().getRules().getValidOffsets(local);
        if (offsets.size() == 1) {
            return local.toInstant(offsets.get(0));
        }
        if (context.dst() == DstRule.REJECT) {
            issue("DST_AMBIGUOUS", Severity.ERROR);
            return null;
        }
        issue("DST_AMBIGUOUS", Severity.WARNING);
        if (offsets.isEmpty()) {
            // In a gap: the clock time never happened; move it forward by the gap, as the zone rules do.
            return local.atZone(context.zone()).toInstant();
        }
        return local.toInstant(context.dst() == DstRule.EARLIER ? offsets.get(0) : offsets.get(1));
    }

    private @Nullable BigDecimal number(@Nullable Object value) {
        try {
            return switch (value) {
                case null -> null;
                case BigDecimal decimal -> decimal;
                case Number other -> new BigDecimal(other.toString());
                default -> new BigDecimal(value.toString().strip());
            };
        } catch (NumberFormatException malformed) {
            issue("BAD_NUMBER", Severity.ERROR);
            return null;
        }
    }

    // Returns Object, not byte[]: a missing or broken value is null, never an empty array that would read as content.
    private @Nullable Object binary(@Nullable String text, String encoding) {
        try {
            return text == null
                    ? null
                    : "hex".equals(encoding)
                            ? HexFormat.of().parseHex(text.strip())
                            : Base64.getMimeDecoder().decode(text);
        } catch (IllegalArgumentException malformed) {
            issue("BAD_BINARY", Severity.ERROR);
            return null;
        }
    }

    private String hmac(String text) {
        return KeyedHash.hex(context.hashKey(), text);
    }

    private static @Nullable Object literal(JsonNode node) {
        return node.isMissingNode() || node.isNull()
                ? null
                : node.isBoolean() ? node.asBoolean() : node.isNumber() ? node.decimalValue() : node.asString();
    }

    private static @Nullable String text(@Nullable Object value) {
        return value == null ? null : value instanceof BigDecimal decimal ? decimal.toPlainString() : value.toString();
    }

    private void issue(String code, Severity severity) {
        issues.add(new Issue(code, severity));
    }
}
