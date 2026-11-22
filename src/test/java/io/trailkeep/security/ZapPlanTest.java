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
    private static final Path OPENAPI = Path.of("security/zap/openapi.yaml");
    private static final String FORMS = "trailkeep-forms";
    private static final String APPLICATION = "http://localhost:8080";
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

    /**
     * The API scan (WP-28 part d, decision Q2): the hand-written description, which {@link OpenApiCoverageTest} keeps
     * complete, is imported into the scan's context after the crawl and before the active scan, so the active scan
     * attacks every operation it names. It has no {@code targetUrl}, which would move the actuator's operations off the
     * management port, and the context covers that port.
     */
    @Test
    void theApiDescriptionIsImportedBeforeTheActiveScanWithTheManagementPortInScope() throws IOException {
        Map<String, Object> plan = load(PLAN);
        List<Map<String, Object>> jobs = list(plan.get("jobs"));
        List<Object> types = jobs.stream().map(job -> job.get("type")).toList();
        assertThat(types.indexOf("openapi"))
                .as("the openapi job's place")
                .isGreaterThan(types.indexOf("spiderAjax"))
                .isLessThan(types.indexOf("activeScan"));
        assertThat(jobs)
                .filteredOn(job -> "openapi".equals(job.get("type")))
                .singleElement()
                .satisfies(job -> assertThat(map(job.get("parameters")))
                        .containsOnly(
                                Map.entry("apiFile", "/zap/wrk/openapi.yaml"), Map.entry("context", "trailkeep")));
        Map<String, Object> context = list(map(plan.get("env")).get("contexts")).getFirst();
        assertThat(strings(context.get("urls"))).contains("http://localhost:8080/", "http://localhost:8081/actuator");
        assertThat(strings(context.get("includePaths"))).contains("http://localhost:8081/actuator.*");
    }

    /**
     * The gate and the reports count the same alerts (review of #211). Without an {@code insights} job, ZAP exits
     * itself on a High insight and discards the session mid-plan, so the reports come out empty while exitStatus still
     * counts the active scan's alerts; with it, the plan stops instead and the jobs after the active scan still run.
     * The scanning jobs stay inside the context, to which ZAP limits the reports.
     */
    @Test
    void theGateAndTheReportsCountTheSameAlerts() throws IOException {
        List<Map<String, Object>> jobs = list(load(PLAN).get("jobs"));
        assertThat(jobs.getFirst()).containsEntry("type", "insights");
        assertThat(map(jobs.getFirst().get("parameters"))).containsEntry("exitAutoLevel", "high");
        int activeScan = jobs.indexOf(jobs.stream()
                .filter(job -> "activeScan".equals(job.get("type")))
                .findFirst()
                .orElseThrow());
        assertThat(jobs.subList(activeScan + 1, jobs.size()))
                .isNotEmpty()
                .allSatisfy(job -> assertThat(job).containsEntry("alwaysRun", true));
        assertThat(jobs)
                .filteredOn(job -> List.of("spider", "spiderAjax").contains(job.get("type")))
                .hasSize(2)
                .allSatisfy(job -> assertThat(map(job.get("parameters"))).containsEntry("context", "trailkeep"));
        assertThat(jobs)
                .filteredOn(job -> "activeScan".equals(job.get("type")))
                .extracting(job -> map(job.get("parameters")).get("context"))
                .containsExactly("trailkeep", FORMS);
        assertThat(jobs)
                .filteredOn(job -> "spiderAjax".equals(job.get("type")))
                .singleElement()
                .satisfies(job -> assertThat(map(job.get("parameters"))).containsEntry("inScopeOnly", true));
    }

    /**
     * The crawl stays capped (WP-28 part e): without the caps the cases list's filter combinations made the active
     * scan run into its time cap on every run. The crawled sites tree is exported with the reports.
     */
    @Test
    void theCrawlIsCappedAndItsRoutesAreExported() throws IOException {
        List<Map<String, Object>> jobs = list(load(PLAN).get("jobs"));
        assertThat(jobs)
                .filteredOn(job -> "spider".equals(job.get("type")))
                .singleElement()
                .satisfies(job -> assertThat(map(job.get("parameters")))
                        .containsEntry("handleParameters", "ignore_value")
                        .hasEntrySatisfying(
                                "maxChildren",
                                value -> assertThat((Integer) value).isBetween(1, 100)));
        assertThat(jobs)
                .filteredOn(job -> "export".equals(job.get("type")))
                .singleElement()
                .satisfies(job -> assertThat(map(job.get("parameters")))
                        .containsEntry("source", "sitestree")
                        .containsEntry("fileName", "/zap/wrk/reports/sites-tree.yaml"));
    }

    /**
     * The form bodies are attacked last (#231). Rules that find a payload an earlier rule stored through a form, shown
     * back escaped, took it as proof of template injection on the cases list and of stored XSS in the CSV export. So
     * the active scan runs twice: first every route with no form body attacked, then, last of all scanning jobs, the
     * form bodies of the POST paths, over the application without its CSV downloads, with no query string attacked.
     * Each part's input vectors come from the activeScan-config job just before it, which sets nothing else (no
     * exclusions, no scanner options); the two caps add up to the single scan's 120 minutes.
     */
    @Test
    void theFormBodiesAreAttackedLastInTheirOwnScan() throws IOException {
        List<Map<String, Object>> jobs = list(load(PLAN).get("jobs"));
        List<Object> types = jobs.stream().map(job -> job.get("type")).toList();
        int first = types.indexOf("activeScan");
        int last = types.lastIndexOf("activeScan");
        assertThat(types.stream().filter("activeScan"::equals)).hasSize(2);
        assertThat(types.subList(last + 1, types.size()))
                .doesNotContainAnyElementsOf(List.of("spider", "spiderAjax", "openapi", "activeScan-config"));

        Map<String, Object> pages = inputVectors(jobs.get(first - 1));
        assertThat(map(pages.get("postData"))).containsOnly(Map.entry("enabled", false));
        assertThat(pages).containsOnlyKeys("postData");
        Map<String, Object> forms = inputVectors(jobs.get(last - 1));
        assertThat(map(forms.get("urlQueryStringAndDataDrivenNodes"))).containsOnly(Map.entry("enabled", false));
        assertThat(map(forms.get("postData"))).containsEntry("enabled", true).doesNotContainValue(false);
        assertThat(forms).containsOnlyKeys("urlQueryStringAndDataDrivenNodes", "postData");

        List<Map<String, Object>> scans = List.of(
                map(jobs.get(first).get("parameters")), map(jobs.get(last).get("parameters")));
        assertThat(scans)
                .allSatisfy(scan -> assertThat(scan)
                        .containsEntry("maxRuleDurationInMins", 5)
                        .hasEntrySatisfying(
                                "maxScanDurationInMins",
                                minutes -> assertThat((Integer) minutes).isPositive()));
        assertThat(scans.stream()
                        .mapToInt(scan -> (Integer) scan.get("maxScanDurationInMins"))
                        .sum())
                .isLessThanOrEqualTo(120);
    }

    /**
     * The second active scan's context is the application without its CSV downloads, as the API description (which
     * {@link OpenApiCoverageTest} keeps equal to what the application serves) lists them. Every POST path must be in
     * it, or its form body is attacked by neither scan; every page must be in it, since a stored payload can show up on
     * any of them; and only the {@code .csv} downloads are left out: attachments never rendered, where the stored-XSS
     * rule would find a case title verbatim by design. ZAP matches a context by path, as here; the Automation Framework
     * includes everything under each of the context's {@code urls}, so they count as include paths. The sign-in and
     * sign-out paths are excluded from both contexts.
     */
    @Test
    void theFormsContextIsTheApplicationWithoutItsCsvDownloads() throws IOException {
        List<Map<String, Object>> contexts = list(map(load(PLAN).get("env")).get("contexts"));
        Map<String, Object> all = contexts.getFirst();
        Map<String, Object> forms = contexts.stream()
                .filter(context -> FORMS.equals(context.get("name")))
                .findFirst()
                .orElseThrow();
        List<String> excludes = strings(forms.get("excludePaths"));
        assertThat(excludes).containsAll(strings(all.get("excludePaths")));
        List<String> includes = new ArrayList<>(strings(forms.get("includePaths")));
        strings(forms.get("urls")).forEach(url -> includes.add(Pattern.quote(url) + ".*"));

        List<String> wrong = new ArrayList<>();
        for (Map.Entry<String, Object> path : map(load(OPENAPI).get("paths")).entrySet()) {
            Map<String, Object> item = map(path.getValue());
            String server = item.containsKey("servers")
                    ? String.valueOf(list(item.get("servers")).getFirst().get("url"))
                    : APPLICATION;
            String url = server + path.getKey().replaceAll("\\{[^}]+}", "1");
            boolean expected = server.equals(APPLICATION)
                    && !matchesAny(strings(all.get("excludePaths")), url)
                    && !path.getKey().endsWith(".csv");
            boolean inForms = matchesAny(includes, url) && !matchesAny(excludes, url);
            if (expected != inForms) {
                wrong.add(path.getKey() + (expected ? ": missing from " : ": wrongly in ") + FORMS);
            }
        }
        assertThat(wrong).isEmpty();
    }

    private static boolean matchesAny(List<String> regexes, String url) {
        return regexes.stream()
                .anyMatch(regex -> Pattern.compile(regex).matcher(url).matches());
    }

    private static Map<String, Object> inputVectors(Map<String, Object> job) {
        assertThat(job).containsEntry("type", "activeScan-config");
        assertThat(job).doesNotContainKeys("parameters", "excludePaths");
        return map(job.get("inputVectors"));
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
    private static List<String> strings(@Nullable Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<String>) Objects.requireNonNull(value);
    }

    @SuppressWarnings("unchecked") // as above
    private static List<Map<String, Object>> list(@Nullable Object value) {
        assertThat(value).isInstanceOf(List.class);
        return (List<Map<String, Object>>) Objects.requireNonNull(value);
    }
}
