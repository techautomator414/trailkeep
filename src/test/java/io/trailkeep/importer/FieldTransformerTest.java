// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.importer.FieldTransformer.Context;
import io.trailkeep.importer.FieldTransformer.DstRule;
import io.trailkeep.importer.FieldTransformer.Issue;
import io.trailkeep.importer.FieldTransformer.Result;
import io.trailkeep.importer.ImportIssue.Severity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Every transform of design §6.3 and every {@code onMissing}/{@code onOverflow} branch, on a synthetic legacy row; and
 * the property that a transform is a pure function of its inputs.
 */
class FieldTransformerTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId CHICAGO = ZoneId.of("America/Chicago");
    private static final SecretKey KEY =
            new SecretKeySpec("synthetic-hash-key-of-32-bytes!!".getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    private static final Map<String, Map<String, String>> LOOKUPS =
            Map.of("status", Map.of("Open", "OPEN", "Closed", "CLOSED"));

    @Test
    void columnsConstantsAndStringTransforms() {
        assertThat(value("\"subject_line\"")).isEqualTo("  Synthetic Subject  ");
        assertThat(value("{\"column\": \"SUBJECT_LINE\"}"))
                .as("column names ignore case")
                .isEqualTo("  Synthetic Subject  ");
        assertThat(value("{\"const\": \"general-review\"}")).isEqualTo("general-review");
        assertThat(value("{\"const\": 7}")).isEqualTo(new BigDecimal("7"));
        assertThat(value("{\"trim\": \"subject_line\"}")).isEqualTo("Synthetic Subject");
        assertThat(value("{\"upper\": {\"trim\": \"subject_line\"}}")).isEqualTo("SYNTHETIC SUBJECT");
        assertThat(value("{\"lower\": \"subject_line\"}")).isEqualTo("  synthetic subject  ");
        assertThat(value("{\"coalesce\": [\"details\", \"blank\", \"(no description in the legacy record)\"]}"))
                .isEqualTo("(no description in the legacy record)");
        assertThat(value("{\"concat\": [\"case_no\", \"missing_value\", {\"const\": \"x\"}], \"separator\": \"-\"}"))
                .isEqualTo("C-100-x");
        assertThat(value("{\"text\": \"subject_line\", \"format\": \"plain\"}")).isEqualTo("  Synthetic Subject  ");
    }

    @Test
    void lookupsFollowOnMissing() {
        assertThat(value("{\"lookup\": \"status\", \"from\": \"state\"}")).isEqualTo("OPEN");
        assertThat(evaluate("{\"lookup\": \"status\", \"from\": \"odd_state\"}"))
                .isEqualTo(failure("LOOKUP_MISSING", Severity.ERROR));
        assertThat(evaluate("{\"lookup\": \"status\", \"from\": \"odd_state\", \"onMissing\": \"issue\"}"))
                .isEqualTo(new Result(null, List.of(new Issue("LOOKUP_MISSING", Severity.WARNING))));
        assertThat(evaluate("{\"lookup\": \"status\", \"from\": \"odd_state\", \"onMissing\": \"default\","
                        + " \"default\": \"OPEN\"}"))
                .isEqualTo(new Result("OPEN", List.of()));
    }

    @Test
    void whenChoosesThenOrElse() {
        assertThat(value("{\"when\": {\"column\": \"flag\", \"equals\": \"Y\"}, \"then\": \"RESTRICTED\","
                        + " \"else\": \"STANDARD\"}"))
                .isEqualTo("RESTRICTED");
        assertThat(value("{\"when\": {\"column\": \"flag\", \"in\": [\"N\", \"n\"]}, \"then\": true, \"else\": false}"))
                .isEqualTo(false);
        assertThat(value("{\"when\": {\"column\": \"blank\", \"notBlank\": true}, \"then\": true, \"else\": false}"))
                .isEqualTo(false);
        assertThat(value(
                        "{\"when\": {\"column\": \"case_no\", \"matches\": \"^C-\\\\d+$\"}, \"then\": 1, \"else\": 0}"))
                .isEqualTo(BigDecimal.ONE);
    }

    @Test
    void regexExtractUsesRe2AndReportsNoMatch() {
        assertThat(value("{\"regexExtract\": \"case_no\", \"pattern\": \"C-(\\\\d+)\"}"))
                .isEqualTo("100");
        assertThat(value("{\"regexExtract\": \"case_no\", \"pattern\": \"C-\\\\d+\"}"))
                .isEqualTo("C-100");
        assertThat(evaluate("{\"regexExtract\": \"case_no\", \"pattern\": \"Z(\\\\d)\"}"))
                .isEqualTo(new Result(null, List.of(new Issue("REGEX_NO_MATCH", Severity.WARNING))));
    }

    @Test
    void timestampsAndDatesAreReadInTheDeclaredZone() {
        assertThat(value("{\"timestamp\": \"opened\"}")).isEqualTo(Instant.parse("2019-03-04T15:00:00Z"));
        assertThat(value("{\"timestamp\": \"opened_text\", \"format\": \"MM/dd/yyyy HH:mm\"}"))
                .isEqualTo(Instant.parse("2019-03-04T15:00:00Z"));
        assertThat(value("{\"timestamp\": \"offset_text\"}")).isEqualTo(Instant.parse("2019-03-04T12:00:00Z"));
        assertThat(value("{\"timestamp\": \"with_offset\"}")).isEqualTo(Instant.parse("2019-03-04T12:00:00Z"));
        assertThat(value("{\"date\": \"opened\"}")).isEqualTo(LocalDate.of(2019, 3, 4));
        assertThat(value("{\"date\": {\"coalesce\": [\"missing_value\", \"found_text\"]}}"))
                .isEqualTo(LocalDate.of(2019, 3, 1));
        assertThat(evaluate("{\"timestamp\": \"case_no\"}")).isEqualTo(failure("BAD_TIMESTAMP", Severity.ERROR));
        assertThat(evaluate("{\"date\": \"case_no\"}")).isEqualTo(failure("BAD_DATE", Severity.ERROR));
    }

    /** A declared format is resolved strictly: an impossible day is an error, never the nearest real one. */
    @Test
    void declaredFormatsRefuseImpossibleDaysInsteadOfMovingThem() {
        assertThat(evaluate("{\"date\": \"feb30\", \"format\": \"yyyy-MM-dd\"}"))
                .isEqualTo(failure("BAD_DATE", Severity.ERROR));
        assertThat(evaluate("{\"date\": \"feb30\", \"format\": \"uuuu-MM-dd\"}"))
                .isEqualTo(failure("BAD_DATE", Severity.ERROR));
        assertThat(evaluate("{\"timestamp\": \"feb30_time\", \"format\": \"MM/dd/yyyy HH:mm\"}"))
                .isEqualTo(failure("BAD_TIMESTAMP", Severity.ERROR));
        assertThat(evaluate("{\"date\": \"feb30\"}")).as("ISO-8601 too").isEqualTo(failure("BAD_DATE", Severity.ERROR));
        assertThat(value("{\"date\": \"leap_day\", \"format\": \"yyyy-MM-dd\"}"))
                .isEqualTo(LocalDate.of(2024, 2, 29));
        assertThat(value("{\"date\": \"quoted_y\", \"format\": \"'y'yyyyMMdd\"}"))
                .as("a quoted y is literal text")
                .isEqualTo(LocalDate.of(2019, 3, 1));
        assertThat(DeclaredFormat.of("yyyy-MM-dd 'year' yy").toString())
                .isEqualTo(DeclaredFormat.of("uuuu-MM-dd 'year' uu").toString());
    }

    /** 2019-11-03 01:30 happened twice in Chicago; 2019-03-10 02:30 never did. */
    @Test
    void daylightSavingAmbiguityFollowsDstAmbiguity() {
        JsonNode overlap = node("{\"timestamp\": \"overlap\"}");
        JsonNode gap = node("{\"timestamp\": \"gap\"}");

        assertThat(FieldTransformer.evaluate(overlap, context(DstRule.EARLIER)))
                .isEqualTo(new Result(
                        Instant.parse("2019-11-03T06:30:00Z"), List.of(new Issue("DST_AMBIGUOUS", Severity.WARNING))));
        assertThat(FieldTransformer.evaluate(overlap, context(DstRule.LATER)).value())
                .isEqualTo(Instant.parse("2019-11-03T07:30:00Z"));
        assertThat(FieldTransformer.evaluate(overlap, context(DstRule.REJECT)))
                .isEqualTo(failure("DST_AMBIGUOUS", Severity.ERROR));
        assertThat(FieldTransformer.evaluate(gap, context(DstRule.EARLIER)).value())
                .isEqualTo(Instant.parse("2019-03-10T08:30:00Z"));
        assertThat(FieldTransformer.evaluate(gap, context(DstRule.REJECT)))
                .isEqualTo(failure("DST_AMBIGUOUS", Severity.ERROR));
    }

    @Test
    void numbersBinariesAndKeyedHashes() {
        assertThat(value("{\"number\": \"amount_text\"}")).isEqualTo(new BigDecimal("12.50"));
        assertThat(value("{\"number\": \"amount\"}")).isEqualTo(new BigDecimal("3"));
        assertThat(evaluate("{\"number\": \"case_no\"}")).isEqualTo(failure("BAD_NUMBER", Severity.ERROR));
        assertThat((byte[]) value("{\"binary\": \"hex\", \"column\": \"hex\"}")).containsExactly(0xCA, 0xFE);
        assertThat((byte[]) value("{\"binary\": \"base64\", \"column\": \"b64\"}"))
                .containsExactly(0xCA, 0xFE);
        assertThat(evaluate("{\"binary\": \"hex\", \"column\": \"case_no\"}"))
                .isEqualTo(failure("BAD_BINARY", Severity.ERROR));
        assertThat(value("{\"hash\": \"case_no\"}"))
                .asString()
                .hasSize(64)
                .isEqualTo(value("{\"hash\": {\"const\": \"C-100\"}}"))
                .isNotEqualTo(FieldTransformer.evaluate(
                                node("{\"hash\": \"case_no\"}"),
                                context(DstRule.REJECT, new SecretKeySpec(new byte[32], "HmacSHA256")))
                        .value());
    }

    @Test
    void maxLengthFollowsOnOverflow() {
        assertThat(evaluate("{\"trim\": \"subject_line\", \"maxLength\": 9}"))
                .isEqualTo(failure("VALUE_TOO_LONG", Severity.ERROR));
        assertThat(evaluate("{\"trim\": \"subject_line\", \"maxLength\": 9, \"onOverflow\": \"truncateWithIssue\"}"))
                .isEqualTo(new Result("Synthetic", List.of(new Issue("VALUE_TRUNCATED", Severity.WARNING))));
        assertThat(evaluate("{\"trim\": \"subject_line\", \"maxLength\": 9, \"onOverflow\": \"continueInComment\"}"))
                .isEqualTo(
                        new Result("Synthetic Subject", List.of(new Issue("DESCRIPTION_CONTINUED", Severity.WARNING))));
        assertThat(evaluate("{\"trim\": \"subject_line\", \"maxLength\": 200}"))
                .isEqualTo(new Result("Synthetic Subject", List.of()));
    }

    @Test
    void legacyTextIsNormalisedFirstAndMissingColumnsAreFatal() {
        assertThat(evaluate("\"dirty\""))
                .isEqualTo(new Result("a\nb", List.of(new Issue("TEXT_NORMALISED", Severity.WARNING))));
        assertThat(evaluate("\"no_such_column\"")).isEqualTo(failure("COLUMN_MISSING", Severity.FATAL));
    }

    /** Property (§11): a transform is a pure function; the same inputs, evaluated twice, give equal results. */
    @Test
    void transformsAreDeterministic() {
        Random random = new Random(26);
        List<String> specs = List.of(
                "{\"trim\": \"v\", \"maxLength\": 5, \"onOverflow\": \"truncateWithIssue\"}",
                "{\"hash\": \"v\"}",
                "{\"concat\": [\"v\", \"v\"], \"separator\": \"|\"}",
                "{\"regexExtract\": \"v\", \"pattern\": \"(\\\\d+)\"}",
                "{\"number\": \"v\"}",
                "{\"timestamp\": \"v\"}",
                "{\"coalesce\": [\"v\", {\"const\": \"none\"}]}");
        for (int i = 0; i < 500; i++) {
            Map<String, @Nullable Object> row = Map.of("v", randomText(random));
            Context context = new Context(row, LOOKUPS, CHICAGO, DstRule.EARLIER, KEY, new CompiledFormats());
            JsonNode spec = node(specs.get(random.nextInt(specs.size())));
            assertThat(FieldTransformer.evaluate(spec, context)).isEqualTo(FieldTransformer.evaluate(spec, context));
        }
    }

    private static String randomText(Random random) {
        StringBuilder text = new StringBuilder();
        int length = random.nextInt(12);
        for (int i = 0; i < length; i++) {
            text.append((char) (random.nextBoolean() ? '0' + random.nextInt(10) : random.nextInt(0x3000)));
        }
        return text.toString();
    }

    private static @Nullable Object value(String spec) {
        Result result = evaluate(spec);
        assertThat(result.issues()).as(spec).isEmpty();
        return result.value();
    }

    private static Result evaluate(String spec) {
        return FieldTransformer.evaluate(node(spec), context(DstRule.REJECT));
    }

    private static Result failure(String code, Severity severity) {
        return new Result(null, List.of(new Issue(code, severity)));
    }

    private static Context context(DstRule dst) {
        return context(dst, KEY);
    }

    private static Context context(DstRule dst, SecretKey key) {
        Map<String, @Nullable Object> row = new HashMap<>();
        row.put("subject_line", "  Synthetic Subject  ");
        row.put("details", null);
        row.put("blank", "   ");
        row.put("case_no", "C-100");
        row.put("missing_value", null);
        row.put("state", "Open");
        row.put("odd_state", "Pending legacy review");
        row.put("flag", "Y");
        row.put("opened", LocalDateTime.of(2019, 3, 4, 9, 0));
        row.put("opened_text", "03/04/2019 09:00");
        row.put("offset_text", "2019-03-04T07:00:00-05:00");
        row.put("with_offset", OffsetDateTime.parse("2019-03-04T07:00:00-05:00"));
        row.put("found_text", "2019-03-01");
        row.put("feb30", "2026-02-30");
        row.put("feb30_time", "02/30/2026 09:00");
        row.put("leap_day", "2024-02-29");
        row.put("quoted_y", "y20190301");
        row.put("overlap", LocalDateTime.of(2019, 11, 3, 1, 30));
        row.put("gap", LocalDateTime.of(2019, 3, 10, 2, 30));
        row.put("amount_text", " 12.50 ");
        row.put("amount", 3);
        row.put("hex", "cafe");
        row.put("b64", "yv4=");
        row.put("dirty", "a\r\nb\u0000");
        return new Context(row, LOOKUPS, CHICAGO, dst, key, new CompiledFormats());
    }

    private static JsonNode node(String json) {
        return JSON.readTree(json);
    }
}
