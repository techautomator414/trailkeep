// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.ops.ManifestSupport.document;
import static io.trailkeep.ops.ManifestSupport.documents;
import static io.trailkeep.ops.ManifestSupport.labels;
import static io.trailkeep.ops.ManifestSupport.selects;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The scheduled staging TTL (#275, docs/design/importer.md §5): a CronJob applied with the importer Job, running {@code
 * expire-staging} on a schedule, one runner at a time, with the import credential and the content claim and nothing
 * else the Job holds. kube-linter and the kustomize renders run on {@code main} only (Part G), so what keeps the
 * schedule live and the credentials apart is checked here, on every build.
 */
class ImporterStagingTtlManifestTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static final Path JOBS = Path.of("deploy/jobs");

    private static final Path CRONJOB = JOBS.resolve("importer/staging-ttl-cronjob.yaml");

    private static final String NAME = "trailkeep-importer-staging-ttl";

    @Test
    void itRunsExpireStagingOnALiveScheduleOneRunAtATime() throws IOException {
        JsonNode cron = cronJob();

        assertThat(cron.at("/spec/suspend").asBoolean(false))
                .as("shipped live: the TTL is the design, not an option")
                .isFalse();
        assertThat(cron.at("/spec/schedule").asString()).isNotBlank();
        assertThat(cron.at("/spec/concurrencyPolicy").asString())
                .as("one runner")
                .isEqualTo("Forbid");
        assertThat(cron.at("/spec/jobTemplate/spec/activeDeadlineSeconds").asInt())
                .isPositive();
        assertThat(env())
                .as("the importer profile's TTL pass, for no run; no mapping, reports, hash key or scan limit")
                .isEqualTo(Map.of(
                        "SPRING_PROFILES_ACTIVE", "prod,importer",
                        "SPRING_MAIN_WEB_APPLICATION_TYPE", "none",
                        "IMPORT_COMMAND", "expire-staging",
                        "IMPORT_CONTENTAREA", "/var/lib/trailkeep/import-content"));
        assertThat(documents(JOBS.resolve("importer/kustomization.yaml"))
                        .getFirst()
                        .path("resources")
                        .toString())
                .as("applied with every importer Job, so it exists from the first command on")
                .contains("\"staging-ttl-cronjob.yaml\"");
    }

    @Test
    void itHoldsTheImportCredentialAndTheAuditKeyButNeitherTheHashKeyNorTheSourcePassword() throws IOException {
        Map<String, List<String>> projected = new TreeMap<>();
        for (JsonNode volume : pod().path("volumes")) {
            assertThat(volume.path("secret").path("secretName").asString(""))
                    .as("no Secret is mounted whole except the CA certificate")
                    .isIn("", "clamav-ca");
            for (JsonNode source : volume.path("projected").path("sources")) {
                List<String> items = new ArrayList<>();
                source.path("secret").path("items").forEach(item -> items.add(item.path("key").asString()));
                projected.put(source.path("secret").path("name").asString(), items);
            }
        }

        assertThat(projected)
                .isEqualTo(Map.of(
                        "trailkeep-import", List.of("spring.datasource.username", "spring.datasource.password"),
                        "trailkeep-web", List.of("app.audit.hmac-key")));
        assertThat(pod().toString()).doesNotContain("trailkeep-import-source", "trailkeep-import-hash-key");
        for (JsonNode entry : container().path("env")) {
            assertThat(entry.has("valueFrom")).as("env %s", entry.path("name")).isFalse();
        }
    }

    @Test
    void itMountsTheJobsContentClaimWritableAndNoOtherClaim() throws IOException {
        JsonNode job = document(JOBS.resolve("importer/importer-job.yaml"), "Job", "trailkeep-importer");
        List<String> claims = new ArrayList<>();
        for (JsonNode volume : pod().path("volumes")) {
            if (volume.has("persistentVolumeClaim")) {
                claims.add(volume.at("/persistentVolumeClaim/claimName").asString());
            }
        }
        String jobClaim = named(job.at("/spec/template/spec/volumes"), "import-content")
                .at("/persistentVolumeClaim/claimName")
                .asString();

        assertThat(claims).as("the content claim alone").containsExactly(jobClaim);
        JsonNode mount = named(container().path("volumeMounts"), "import-content");
        assertThat(mount.path("mountPath").asString()).isEqualTo(env().get("IMPORT_CONTENTAREA"));
        assertThat(mount.path("readOnly").asBoolean()).as("each expired run's folder is deleted").isFalse();
        assertThat(container().at("/securityContext/readOnlyRootFilesystem").asBoolean())
                .isTrue();
        assertThat(pod().path("automountServiceAccountToken").asBoolean(true)).isFalse();
    }

    @Test
    void itHasTheImportersNetworkWithoutTheSourceRouteAndTheExternalVariantDropsTheCaCertificate()
            throws IOException {
        Map<String, String> labels = labels(cronJob().at("/spec/jobTemplate/spec/template"));
        Path policies = JOBS.resolve("importer/networkpolicy.yaml");

        assertThat(selects(document(policies, "NetworkPolicy", "trailkeep-importer"), labels))
                .isTrue();
        assertThat(selects(document(policies, "NetworkPolicy", "trailkeep-importer-source"), labels))
                .as("no route to the source")
                .isFalse();
        assertThat(selects(
                        document(Path.of("deploy/base/networkpolicy.yaml"), "NetworkPolicy", "trailkeep-egress"),
                        labels))
                .as("not the web pods' egress (identity provider, mail)")
                .isFalse();

        JsonNode overlay = documents(JOBS.resolve("importer-external-scanning/kustomization.yaml"))
                .getFirst();
        List<JsonNode> patches = new ArrayList<>();
        for (JsonNode candidate : overlay.path("patches")) {
            JsonNode parsed = YAML.readTree(candidate.path("patch").asString());
            if (NAME.equals(parsed.at("/metadata/name").asString())) {
                patches.add(parsed.at("/spec/jobTemplate/spec/template/spec"));
            }
        }
        assertThat(patches).as("the external variant patches the CronJob once").hasSize(1);
        JsonNode patch = patches.getFirst();
        assertThat(patch.at("/volumes/0/name").asString()).isEqualTo("clamav-ca");
        assertThat(patch.at("/volumes/0/$patch").asString()).isEqualTo("delete");
        assertThat(patch.at("/containers/0/volumeMounts/0/mountPath").asString())
                .isEqualTo("/etc/trailkeep/clamav-ca");
        assertThat(patch.at("/containers/0/volumeMounts/0/$patch").asString()).isEqualTo("delete");
    }

    private static JsonNode named(JsonNode list, String name) {
        List<JsonNode> found = new ArrayList<>();
        list.forEach(entry -> {
            if (name.equals(entry.path("name").asString())) {
                found.add(entry);
            }
        });
        assertThat(found).as(name).hasSize(1);
        return found.getFirst();
    }

    private static JsonNode cronJob() throws IOException {
        return document(CRONJOB, "CronJob", NAME);
    }

    private static JsonNode pod() throws IOException {
        return cronJob().at("/spec/jobTemplate/spec/template/spec");
    }

    private static JsonNode container() throws IOException {
        return pod().at("/containers/0");
    }

    private static Map<String, String> env() throws IOException {
        Map<String, String> env = new TreeMap<>();
        container().path("env").forEach(e -> env.put(e.path("name").asString(), e.path("value").asString()));
        return env;
    }
}
