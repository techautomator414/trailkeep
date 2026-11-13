// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The migration Job's manifests (issue #205, BUILD_PLAN §8 rule 7, §16): the schema owner's credential lives in a
 * Secret of its own that only this Job mounts, the pod is hardened like the importer's, and its network reaches the
 * database and nothing else. kube-linter runs on {@code main} only (Part G), so least privilege is checked here.
 */
class MigrateJobManifestTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static final Path JOB = Path.of("deploy/jobs/migrate/migrate-job.yaml");

    private static final Path POLICY = Path.of("deploy/jobs/migrate/networkpolicy.yaml");

    private static final String SECRET = "trailkeep-migrate";

    @Test
    void theJobSeesTheOwnerCredentialAndNothingElse() throws IOException {
        Map<String, List<String>> projected = new TreeMap<>();
        for (JsonNode volume : pod().get("volumes")) {
            assertThat(volume.path("secret").path("secretName").asString(""))
                    .as("no Secret is mounted whole except the CA certificate")
                    .isIn("", "clamav-ca");
            for (JsonNode source : volume.path("projected").path("sources")) {
                List<String> items = new ArrayList<>();
                source.path("secret").path("items").forEach(item -> {
                    assertThat(item.path("path").asString())
                            .isEqualTo(item.path("key").asString());
                    items.add(item.path("key").asString());
                });
                projected.put(source.path("secret").path("name").asString(), items);
            }
        }

        assertThat(projected)
                .as("no web, purge or import Secret, no HMAC or hash key, no OIDC client secret")
                .isEqualTo(Map.of(SECRET, List.of("spring.datasource.username", "spring.datasource.password")));
    }

    @Test
    void noOtherWorkloadMountsTheOwnerSecret() throws IOException {
        List<Path> mentions = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("deploy"))) {
            for (Path file : files.filter(f -> f.toString().endsWith(".yaml")).toList()) {
                boolean ownDirectory = file.startsWith("deploy/jobs/migrate")
                        || file.startsWith("deploy/jobs/migrate-external-scanning");
                boolean mentioned = Files.readAllLines(file).stream()
                        .map(String::strip)
                        .filter(line -> !line.startsWith("#"))
                        .anyMatch(line -> line.contains(SECRET));
                if (mentioned && !ownDirectory) {
                    mentions.add(file);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        assertThat(mentions)
                .as("manifests outside the migrate directories that name the owner Secret")
                .isEmpty();
    }

    @Test
    void theJobRunsTheMigrateProfileWithoutWebServerAndIsHardenedLikeTheImporter() throws IOException {
        JsonNode container = pod().at("/containers/0");
        Map<String, String> env = new TreeMap<>();
        container
                .path("env")
                .forEach(e -> env.put(e.path("name").asString(), e.path("value").asString()));

        assertThat(env)
                .containsEntry("SPRING_PROFILES_ACTIVE", "prod,migrate")
                .containsEntry("SPRING_MAIN_WEB_APPLICATION_TYPE", "none");
        assertThat(container.at("/envFrom/0/configMapRef/name").asString()).isEqualTo("trailkeep-config");
        assertThat(container.at("/securityContext/readOnlyRootFilesystem").asBoolean())
                .isTrue();
        assertThat(container.at("/securityContext/allowPrivilegeEscalation").asBoolean())
                .isFalse();
        assertThat(container.at("/securityContext/runAsNonRoot").asBoolean()).isTrue();
        assertThat(container.at("/securityContext/capabilities/drop").toString())
                .isEqualTo("[\"ALL\"]");
        assertThat(pod().at("/securityContext/seccompProfile/type").asString()).isEqualTo("RuntimeDefault");
        assertThat(pod().path("automountServiceAccountToken").asBoolean(true)).isFalse();
        assertThat(pod().path("restartPolicy").asString()).isEqualTo("Never");
        assertThat(job().at("/spec/backoffLimit").asInt())
                .as("a failed migration is read, not retried")
                .isZero();
        assertThat(job().at("/spec/activeDeadlineSeconds").isNumber()).isTrue();
        assertThat(job().at("/spec/ttlSecondsAfterFinished").isNumber()).isTrue();
        assertThat(job().at("/spec/template/metadata/labels/app.kubernetes.io~1component")
                        .asString())
                .isEqualTo("migrate");
    }

    @Test
    void theMigrationReachesOnlyDnsAndTheDatabaseAndNothingConnectsToIt() throws IOException {
        JsonNode policy = documents(POLICY).getFirst().get("spec");

        assertThat(documents(POLICY)).hasSize(1);
        assertThat(policy.at("/podSelector/matchLabels/app.kubernetes.io~1component")
                        .asString())
                .isEqualTo("migrate");
        assertThat(policy.path("policyTypes").toString()).isEqualTo("[\"Ingress\",\"Egress\"]");
        assertThat(policy.has("ingress")).as("no ingress rule").isFalse();
        Set<Integer> ports = new TreeSet<>();
        policy.path("egress")
                .forEach(rule -> rule.path("ports")
                        .forEach(port -> ports.add(port.path("port").asInt())));
        assertThat(ports)
                .as("DNS and the database ports; no identity provider, SMTP or clamd")
                .containsExactly(53, 2484, 5353, 5432);
        assertThat(policy.path("egress")).hasSize(2);
        assertThat(documents(Path.of("deploy/base/networkpolicy.yaml")).stream()
                        .filter(p ->
                                "trailkeep-egress".equals(p.at("/metadata/name").asString()))
                        .findFirst()
                        .orElseThrow()
                        .at("/spec/podSelector/matchExpressions/0/values")
                        .toString())
                .as("the base egress, which reaches the identity provider and the mail relay, excludes this pod")
                .contains("migrate");
    }

    @Test
    void theExternalScanningVariantOnlyDropsTheClamdCaVolume() throws IOException {
        JsonNode overlay = documents(Path.of("deploy/jobs/migrate-external-scanning/kustomization.yaml"))
                .getFirst();
        JsonNode pod = YAML.readTree(overlay.at("/patches/0/patch").asString()).at("/spec/template/spec");

        assertThat(overlay.path("resources").toString()).isEqualTo("[\"../migrate\"]");
        assertThat(overlay.path("patches")).hasSize(1);
        assertThat(overlay.at("/patches/0/patch").asString()).contains("trailkeep-migrate");
        assertThat(pod.at("/containers/0/volumeMounts/0/mountPath").asString()).isEqualTo("/etc/trailkeep/clamav-ca");
        assertThat(pod.at("/containers/0/volumeMounts/0/$patch").asString()).isEqualTo("delete");
        assertThat(pod.at("/volumes/0/name").asString()).isEqualTo("clamav-ca");
        assertThat(pod.at("/volumes/0/$patch").asString()).isEqualTo("delete");
    }

    private static JsonNode job() throws IOException {
        List<JsonNode> documents = documents(JOB);
        assertThat(documents).hasSize(1);
        return documents.getFirst();
    }

    private static JsonNode pod() throws IOException {
        return job().at("/spec/template/spec");
    }

    private static List<JsonNode> documents(Path file) throws IOException {
        return YAML.readerFor(JsonNode.class)
                .<JsonNode>readValues(Files.readString(file))
                .readAll();
    }
}
