// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Where the image build's dependencies come from (issue 290, after 230 and 240). Images that are scanned and never
 * published (main.yml's image job, zap.yml's scan image) build offline from their job's own Maven cache. The published
 * image (release.yml) builds cold from Maven Central, and its job restores no Maven repository, so nothing cached can
 * reach a release. The script defaults to cold, a cold build drops any seed, the offline build cannot reach Central,
 * and the Containerfile goes offline only when the seed is in its context.
 */
class ImageBuildDependenciesTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();
    private static final Pattern BUILD = Pattern.compile("build-image\\.sh\\s+\"[^\"]+\"\\s+\"[^\"]+\"(?:\\s+(\\S+))?");

    @Test
    void scannedImagesBuildOfflineAndThePublishedImageBuildsCold() throws IOException {
        assertThat(modes("main.yml")).containsExactly(Map.entry("image", "offline"));
        assertThat(modes("zap.yml")).containsExactly(Map.entry("scan", "offline"));
        assertThat(modes("release.yml")).hasSize(1).containsValue("cold");
    }

    @Test
    void theReleaseImageJobRestoresNoMavenRepository() throws IOException {
        JsonNode jobs = YAML.readTree(Files.readString(Path.of(".github/workflows/release.yml")))
                .get("jobs");
        String job = modes("release.yml").keySet().iterator().next();

        List<String> cached = new ArrayList<>();
        for (JsonNode step : jobs.get(job).get("steps")) {
            String path = step.at("/with/path").asString("");
            if (step.path("uses").asString("").startsWith("actions/cache") && path.contains(".m2")) {
                cached.add(path);
            }
            if (step.path("uses").asString("").startsWith("actions/setup-java")) {
                assertThat(step.at("/with/cache").asString(""))
                        .as("setup-java's own cache in " + job)
                        .isEmpty();
            }
        }
        assertThat(cached)
                .as("Maven repository caches in release.yml's " + job + " job")
                .isEmpty();
    }

    @Test
    void theReleaseImageJobDownloadsTheTrivyDatabasesColdAndMainCachesThem() throws IOException {
        String job = modes("release.yml").keySet().iterator().next();

        List<String> release = trivyDbCacheInputs("release.yml", job);
        assertThat(release)
                .as("trivy-db steps in release.yml's " + job + " job")
                .containsExactly("false");
        for (JsonNode step : steps("release.yml", job)) {
            assertThat(step.path("uses").asString("").startsWith("actions/cache")
                            && step.at("/with/path").asString("").contains("trivy"))
                    .as("a Trivy cache step in release.yml's " + job + " job")
                    .isFalse();
        }
        for (Map.Entry<String, String> image : modes("main.yml").entrySet()) {
            assertThat(trivyDbCacheInputs("main.yml", image.getKey())).containsOnly("true");
        }
        assertThat(trivyDbCacheInputs("main.yml", "trivy-sbom")).containsExactly("true");
    }

    @Test
    void theScriptDefaultsToColdDropsTheSeedWhenColdAndBlocksCentralWhenOffline() throws IOException {
        String script = Files.readString(Path.of(".github/scripts/build-image.sh"));

        assertThat(script)
                .contains("mode=\"${3:-cold}\"")
                .contains("rm -rf target/image/m2-seed\n  echo \"Maven repository seed: none")
                .contains("--add-host repo.maven.apache.org:127.0.0.1", "--add-host repo1.maven.org:127.0.0.1");
        assertThat(Files.readString(Path.of("Containerfile")))
                .contains("if [ -d \"$repository\" ]; then offline=\"-o -Dmaven.repo.local=$repository\"")
                .contains("./mvnw -B -ntp $offline -DskipTests package");
        assertThat(Files.readString(Path.of(".dockerignore"))).contains("!target/image/m2-seed/**");
    }

    @Test
    void theResolverWaitsOutLongerRateLimitWindowsOnTheColdPaths() throws IOException {
        assertThat(Files.readAllLines(Path.of(".mvn/maven.config")))
                .contains(
                        "-Daether.transport.http.retryHandler.count=10",
                        "-Daether.transport.http.retryHandler.intervalMax=120000",
                        "-Daether.transport.http.retryHandler.serviceUnavailable=429,503");
    }

    private static JsonNode steps(String workflow, String job) throws IOException {
        return YAML.readTree(Files.readString(Path.of(".github/workflows", workflow)))
                .get("jobs")
                .get(job)
                .get("steps");
    }

    /** The explicit {@code with.cache} of every trivy-db step in a job. */
    private static List<String> trivyDbCacheInputs(String workflow, String job) throws IOException {
        List<String> inputs = new ArrayList<>();
        for (JsonNode step : steps(workflow, job)) {
            if (step.path("uses").asString("").equals("./.github/actions/trivy-db")) {
                inputs.add(step.at("/with/cache").asString("<unset>"));
            }
        }
        return inputs;
    }

    /** Each job that builds the image, with the dependency mode it passes (blank if it passes none). */
    private static Map<String, String> modes(String workflow) throws IOException {
        JsonNode jobs = YAML.readTree(Files.readString(Path.of(".github/workflows", workflow)))
                .get("jobs");
        Map<String, String> modes = new TreeMap<>();
        for (Map.Entry<String, JsonNode> job : jobs.properties()) {
            for (JsonNode step : job.getValue().path("steps")) {
                Matcher build = BUILD.matcher(step.path("run").asString(""));
                if (build.find()) {
                    modes.put(job.getKey(), build.group(1) == null ? "" : build.group(1));
                }
            }
        }
        return modes;
    }
}
