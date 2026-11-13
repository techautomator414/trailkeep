// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Guards the DAST gate's configuration on every PR (WP-28 part c), although ZAP itself runs only nightly and on release
 * tags: the plan fails on Medium or higher, its alertFilter job is empty in the file (suppressions come only from
 * {@code security/zap/suppressions.yaml}), and every suppression carries a rule, a URL pattern that compiles, a written
 * justification, the pull request that approved it, the approval date, and an expiry at most 90 days later.
 */
class ZapPlanTest {

    private static final Path PLAN = Path.of("security/zap/plan.yaml");
    private static final Path SUPPRESSIONS = Path.of("security/zap/suppressions.yaml");
    private static final Pattern PULL_REQUEST =
            Pattern.compile("https://github\\.com/techautomator414/trailkeep/pull/[0-9]+");
    private static final int MAX_DAYS = 90;
    private static final int MIN_JUSTIFICATION = 40;

    @Test
    void thePlanFailsOnMediumOrHigherAndSuppressesNothingItself() throws IOException {
        List<Map<String, Object>> jobs = list(load(PLAN).get("jobs"));
        assertThat(jobs)
                .filteredOn(job -> "alertFilter".equals(job.get("type")))
                .singleElement()
                .satisfies(job -> assertThat(list(job.get("alertFilters"))).isEmpty());
        assertThat(jobs)
                .filteredOn(job -> "exitStatus".equals(job.get("type")))
                .singleElement()
                .satisfies(job -> assertThat(map(job.get("parameters"))).containsEntry("errorLevel", "Medium"));
        assertThat(jobs)
                .extracting(job -> job.get("type"))
                .contains("activeScan")
                .last()
                .isEqualTo("exitStatus");
    }

    @Test
    void everySuppressionIsJustifiedReviewedAndExpires() throws IOException {
        List<String> problems = new ArrayList<>();
        for (Map<String, Object> entry : list(load(SUPPRESSIONS).get("suppressions"))) {
            String id = String.valueOf(entry.get("ruleId"));
            if (!id.matches("[0-9]+")) {
                problems.add(entry + ": ruleId is not a ZAP rule id");
            }
            try {
                Pattern.compile(String.valueOf(entry.get("url")));
            } catch (PatternSyntaxException e) {
                problems.add(id + ": url is not a regular expression");
            }
            if (String.valueOf(entry.get("justification")).strip().length() < MIN_JUSTIFICATION) {
                problems.add(id + ": no written justification");
            }
            if (!PULL_REQUEST.matcher(String.valueOf(entry.get("reviewedIn"))).matches()) {
                problems.add(id + ": reviewedIn is not a pull request of this repository");
            }
            try {
                LocalDate reviewed = date(entry.get("reviewed"));
                LocalDate expires = date(entry.get("expires"));
                if (expires.isAfter(reviewed.plusDays(MAX_DAYS)) || expires.isBefore(reviewed)) {
                    problems.add(id + ": expires more than " + MAX_DAYS + " days after review");
                }
            } catch (DateTimeParseException e) {
                problems.add(id + ": reviewed and expires must be dates (YYYY-MM-DD)");
            }
        }
        assertThat(problems).isEmpty();
    }

    /** A YAML date: SnakeYAML reads an unquoted {@code 2026-11-13} as a {@link Date} at midnight UTC. */
    private static LocalDate date(@Nullable Object value) {
        return value instanceof Date date
                ? date.toInstant().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.parse(String.valueOf(value));
    }

    private static Map<String, Object> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file)) {
            return map(new Yaml(new SafeConstructor(new LoaderOptions())).load(reader));
        }
    }

    @SuppressWarnings("unchecked") // SnakeYAML's safe constructor yields maps and lists of plain values
    private static Map<String, Object> map(@Nullable Object value) {
        assertThat(value).isInstanceOf(Map.class);
        return (Map<String, Object>) Objects.requireNonNull(value);
    }

    @SuppressWarnings("unchecked") // as above
    private static List<Map<String, Object>> list(@Nullable Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) Objects.requireNonNull(value);
    }
}
