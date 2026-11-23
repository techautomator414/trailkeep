// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.ops.EvidencePackManifest.Row;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The release evidence pack (WP-30 part d; BUILD_PLAN §12.3). {@code .github/release/evidence-pack.txt} lists every
 * file a release carries; this test keeps it and {@code release.yml} in step: every artifact the pack takes is uploaded
 * by a job of the release run, the GitHub Release waits for all of them, publishing waits for the preflight and every
 * gate, the preflight checks the tag, the secrets and the repository first, the pack is assembled, signed and checked
 * before the release is created, every checked-in source exists, and the pack covers each item the plan asks for.
 * {@code EvidencePackScriptTest} runs the script that builds the pack.
 */
class ReleaseEvidencePackTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Path WORKFLOWS = Path.of(".github/workflows");

    /**
     * What the plan asks the pack to hold: BUILD_PLAN §12.3, then WP-30 (performance, restore drill, runbook,
     * CHANGELOG, signed tag) and the security documents. Each must appear in some row's requirement.
     */
    private static final List<String> REQUIRED = List.of(
            "SBOM (CycloneDX JSON)",
            "SAST report",
            "SCA report",
            "DAST report",
            "Suppressions with justifications",
            "Licence report",
            "Test and coverage report",
            "Oracle integration test results",
            "PostgreSQL integration test results",
            "Accessibility Conformance Report (VPAT 2.5)",
            "axe results",
            "Image digest",
            "Image Sigstore signature",
            "SLSA provenance",
            "Flyway schema version",
            "Performance",
            "Restore drill",
            "runbook",
            "CHANGELOG section",
            "Signed tag",
            "Security test map",
            "Threat model",
            "Data map");

    private final List<Row> rows = EvidencePackManifest.rows();

    @Test
    void everyArtifactThePackTakesIsUploadedByTheReleaseRun() throws IOException {
        Set<String> uploaded = uploads("release.yml");
        for (JsonNode job : workflow("release.yml").get("jobs")) {
            String uses = job.path("uses").asString("");
            if (uses.startsWith("./.github/workflows/")) {
                uploaded.addAll(uploads(uses.substring("./.github/workflows/".length())));
            }
        }
        List<String> missing = new ArrayList<>();
        for (Row row : rows) {
            if (row.fromArtifact() && !uploaded.contains(row.artifact())) {
                missing.add(row.file() + " <- " + row.artifact());
            }
        }
        assertThat(missing)
                .as("pack files whose artifact no release job uploads")
                .isEmpty();
    }

    @Test
    void theGitHubReleaseWaitsForEveryOtherJob() throws IOException {
        JsonNode jobs = workflow("release.yml").get("jobs");
        Set<String> waited = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>(needs(jobs.get("github-release")));
        while (!queue.isEmpty()) {
            String job = queue.pop();
            if (waited.add(job)) {
                queue.addAll(needs(jobs.get(job)));
            }
        }
        Set<String> others = new TreeSet<>();
        jobs.properties().forEach(job -> others.add(job.getKey()));
        others.remove("github-release");
        assertThat(waited).isEqualTo(others);
    }

    @Test
    void publishingWaitsForThePreflightAndEveryGate() throws IOException {
        JsonNode jobs = workflow("release.yml").get("jobs");
        assertThat(needs(jobs.get("publish"))).contains("preflight", "main-gates", "verify", "zap", "perf");
        assertThat(jobs.at("/perf/uses").asString("")).isEqualTo("./.github/workflows/perf.yml");
        assertThat(jobs.at("/perf/with/maven-cache").asBoolean(true))
                .as("release.yml's perf restores no Maven cache")
                .isFalse();
        assertThat(workflow("nightly.yml").at("/jobs/perf/uses").asString(""))
                .isEqualTo("./.github/workflows/perf.yml");
    }

    @Test
    void thePreflightChecksTheTagAndTheRepositoryBeforeAnythingIsBuilt() throws IOException {
        JsonNode preflight = workflow("release.yml").at("/jobs/preflight");
        assertThat(needs(preflight)).isEmpty();
        String runs = runs(preflight);
        assertThat(runs)
                .contains("git/ref/tags/${GITHUB_REF_NAME}", ".verification.verified")
                .contains("if [ \"${target}\" != \"${GITHUB_SHA}\" ]")
                .contains("if [ \"${version%%.*}\" -ge 1 ] && [ \"${verified}\" != true ]")
                .contains("evidence-pack.py preflight --tag \"${GITHUB_REF_NAME}\" --out preflight");
        assertThat(preflight.findValuesAsString("SONAR")).containsExactly("${{ secrets.SONAR_TOKEN != '' }}");
        assertThat(preflight.findValuesAsString("NVD")).containsExactly("${{ secrets.NVD_API_KEY != '' }}");
        assertThat(runs).contains("[ \"${SONAR}\" != true ] || [ \"${NVD}\" != true ]");
    }

    @Test
    void theReleaseAssemblesSignsAndChecksThePackBeforePublishingIt() throws IOException {
        String runs = runs(workflow("release.yml").at("/jobs/github-release"));
        int assemble = runs.indexOf("evidence-pack.py assemble");
        int sign = runs.indexOf("cosign sign-blob --yes --bundle pack/SHA256SUMS.sigstore.json pack/SHA256SUMS");
        int verify = runs.indexOf("cosign verify-blob --bundle pack/SHA256SUMS.sigstore.json");
        int check = runs.indexOf("evidence-pack.py check");
        int publish = runs.indexOf("gh release create \"${GITHUB_REF_NAME}\" pack/*");
        assertThat(List.of(assemble, sign, verify, check, publish))
                .doesNotContain(-1)
                .isSorted();
    }

    @Test
    void everyCheckedInSourceRequiredNowExists() {
        for (Row row : rows) {
            if (row.source().startsWith("repo:") && row.since().equals("all")) {
                assertThat(Path.of(row.source().substring("repo:".length())))
                        .as(row.file())
                        .exists();
            }
        }
    }

    @Test
    void thePackCoversEveryItemThePlanAsksFor() {
        String requirements =
                String.join("\n", rows.stream().map(Row::requirement).toList()).toLowerCase(Locale.ROOT);
        assertThat(REQUIRED.stream().filter(item -> !requirements.contains(item.toLowerCase(Locale.ROOT))))
                .as("plan items with no row in " + EvidencePackManifest.PATH)
                .isEmpty();
    }

    private static JsonNode workflow(String name) throws IOException {
        return YAML.readTree(Files.readString(WORKFLOWS.resolve(name)));
    }

    /** Every artifact name a workflow's jobs upload, with {@code ${{ matrix.identity }}} expanded. */
    private static Set<String> uploads(String name) throws IOException {
        Set<String> names = new TreeSet<>();
        for (JsonNode job : workflow(name).get("jobs")) {
            List<String> identities = new ArrayList<>();
            job.at("/strategy/matrix/identity").forEach(identity -> identities.add(identity.asString()));
            for (JsonNode step : job.path("steps")) {
                if (!step.path("uses").asString("").startsWith("actions/upload-artifact")) {
                    continue;
                }
                String upload = step.at("/with/name").asString("");
                if (upload.contains("${{ matrix.identity }}")) {
                    identities.forEach(identity -> names.add(upload.replace("${{ matrix.identity }}", identity)));
                } else {
                    names.add(upload);
                }
            }
        }
        return names;
    }

    private static List<String> needs(JsonNode job) {
        JsonNode needs = job.path("needs");
        List<String> names = new ArrayList<>();
        if (needs.isString()) {
            names.add(needs.asString());
        } else {
            needs.forEach(need -> names.add(need.asString()));
        }
        return names;
    }

    private static String runs(JsonNode job) {
        StringBuilder runs = new StringBuilder();
        job.path("steps")
                .forEach(step -> runs.append(step.path("run").asString("")).append('\n'));
        return runs.toString();
    }
}
