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
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * The importer Job's manifests (WP-26 part ab, docs/design/importer.md §10): which secret files the pod sees, where
 * its writable folders are, and what its network allows. kube-linter and the kustomize renders run on {@code main}
 * only (Part G), so the properties that matter for least privilege are checked here, on every build.
 */
class ImporterJobManifestTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static final Path JOB = Path.of("deploy/jobs/importer/importer-job.yaml");

    private static final Path POLICY = Path.of("deploy/jobs/importer/networkpolicy.yaml");

    @Test
    void theJobSeesTheImportCredentialAndBothKeysButNeitherTheWholeWebSecretNorTheSourcePassword() throws IOException {
        JsonNode volumes = pod().get("volumes");
        Map<String, List<String>> projected = new TreeMap<>();
        for (JsonNode volume : volumes) {
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
                .as("configtree file names, each Secret's items named; no source password without the component")
                .isEqualTo(Map.of(
                        "trailkeep-import", List.of("spring.datasource.username", "spring.datasource.password"),
                        "trailkeep-import-hash-key", List.of("import.hash-key"),
                        "trailkeep-web", List.of("app.audit.hmac-key")));
        assertThat(mountOf("secrets").path("mountPath").asString()).isEqualTo("/etc/trailkeep/secrets");
        assertThat(mountOf("secrets").path("readOnly").asBoolean()).isTrue();
    }

    @Test
    void sourceAccessIsOneComponentAddingTheSourcePasswordAndTheRouteTogetherToEitherVariant() throws IOException {
        JsonNode component = documents(Path.of("deploy/jobs/components/source-access/kustomization.yaml"))
                .getFirst();
        JsonNode ops = YAML.readTree(component.at("/patches/0/patch").asString());

        assertThat(component.path("kind").asString()).isEqualTo("Component");
        assertThat(component.path("patches")).hasSize(1);
        assertThat(component.at("/patches/0/target/name").asString()).isEqualTo("trailkeep-importer");
        assertThat(ops.toString())
                .as("exactly the source password, appended to the secret files behind a test op, and the label")
                .isEqualTo("[{\"op\":\"test\",\"path\":\"/spec/template/spec/volumes/0/name\",\"value\":\"secrets\"},"
                        + "{\"op\":\"add\",\"path\":\"/spec/template/spec/volumes/0/projected/sources/-\","
                        + "\"value\":{\"secret\":{\"name\":\"trailkeep-import-source\",\"items\":[{\"key\":"
                        + "\"import.source.password\",\"path\":\"import.source.password\"}]}}},"
                        + "{\"op\":\"add\",\"path\":\"/spec/template/metadata/labels/trailkeep.io~1source-egress\","
                        + "\"value\":\"true\"}]");
        assertThat(pod().at("/volumes/0/name").asString()).isEqualTo("secrets");
        for (String variant : List.of("importer", "importer-external-scanning")) {
            JsonNode applied = documents(Path.of("deploy/jobs/" + variant + "-source-access/kustomization.yaml"))
                    .getFirst();
            assertThat(applied.path("resources").toString()).isEqualTo("[\"../" + variant + "\"]");
            assertThat(applied.path("components").toString()).isEqualTo("[\"../components/source-access\"]");
        }
        assertThat(Files.readString(Path.of("deploy/jobs/importer-external-scanning/kustomization.yaml")))
                .as("the external variant without the component adds neither")
                .doesNotContain("import-source", "source-egress");
    }

    @Test
    void theContentAreaAndTheReportsAreWritableClaimsOfTheirOwnAndTheDataStaysReadOnly() throws IOException {
        Map<String, String> env = env();
        Set<String> claims = new TreeSet<>();
        for (String folder : List.of("IMPORT_CONTENTAREA", "IMPORT_REPORTSDIR")) {
            JsonNode mount = mountAt(Objects.requireNonNull(env.get(folder), folder));
            assertThat(mount.path("readOnly").asBoolean()).as(folder).isFalse();
            JsonNode claim = volume(mount.path("name").asString()).path("persistentVolumeClaim");
            assertThat(claim.path("readOnly").asBoolean()).as(folder).isFalse();
            claims.add(claim.path("claimName").asString());
        }

        assertThat(claims).as("two claims, not one folder inside the other").hasSize(2);
        JsonNode data = mountOf("import-data");
        assertThat(data.path("readOnly").asBoolean()).isTrue();
        assertThat(volume("import-data")
                        .path("persistentVolumeClaim")
                        .path("readOnly")
                        .asBoolean())
                .isTrue();
        assertThat(env.get("IMPORT_MAPPING")).startsWith(data.path("mountPath").asString() + "/");
        assertThat(env)
                .containsEntry("SPRING_PROFILES_ACTIVE", "prod,importer")
                .containsKeys("IMPORT_COMMAND", "IMPORT_RUN");
        assertThat(pod().at("/containers/0/securityContext/readOnlyRootFilesystem")
                        .asBoolean())
                .isTrue();
    }

    @Test
    void theImporterReachesOnlyDnsTheDatabaseAndClamdAndTheSourceOnlyWhenLabelledForIt() throws IOException {
        Map<String, JsonNode> policies = new TreeMap<>();
        for (JsonNode policy : documents(POLICY)) {
            policies.put(policy.at("/metadata/name").asString(), policy.get("spec"));
        }
        JsonNode own = Objects.requireNonNull(policies.get("trailkeep-importer"));
        JsonNode source = Objects.requireNonNull(policies.get("trailkeep-importer-source"));

        assertThat(policies).containsOnlyKeys("trailkeep-importer", "trailkeep-importer-source");
        assertThat(own.path("policyTypes").toString()).isEqualTo("[\"Ingress\",\"Egress\"]");
        assertThat(own.has("ingress"))
                .as("no ingress rule: nothing connects to the pod")
                .isFalse();
        assertThat(ports(own)).containsExactly(53, 2484, 3311, 5353, 5432);
        assertThat(own.at("/egress/2/to/0/podSelector/matchLabels/app.kubernetes.io~1name")
                        .asString())
                .as("the rule importer-external-scanning removes, by index, after its test op")
                .isEqualTo("clamav");
        assertThat(source.at("/podSelector/matchLabels/trailkeep.io~1source-egress")
                        .asString())
                .isEqualTo("true");
        assertThat(job().at("/spec/template/metadata/labels").has("trailkeep.io/source-egress"))
                .as("as shipped, a Job has no route to the source")
                .isFalse();
        assertThat(source.path("policyTypes").toString()).isEqualTo("[\"Egress\"]");
    }

    @Test
    void theBaseEgressThatReachesTheIdentityProviderAndMailExcludesTheImporterPod() throws IOException {
        JsonNode egress = documents(Path.of("deploy/base/networkpolicy.yaml")).stream()
                .filter(policy ->
                        "trailkeep-egress".equals(policy.at("/metadata/name").asString()))
                .findFirst()
                .orElseThrow();

        JsonNode exclusion = egress.at("/spec/podSelector/matchExpressions/0");

        assertThat(exclusion.path("key").asString()).isEqualTo("app.kubernetes.io/component");
        assertThat(exclusion.path("operator").asString()).isEqualTo("NotIn");
        assertThat(exclusion.path("values").toString()).isEqualTo("[\"importer\"]");
        assertThat(job().at("/spec/template/metadata/labels/app.kubernetes.io~1component")
                        .asString())
                .isEqualTo("importer");
    }

    @Test
    void theExternalVariantDropsEverythingOnlyClamdNeeds() throws IOException {
        JsonNode overlay = documents(Path.of("deploy/jobs/importer-external-scanning/kustomization.yaml"))
                .getFirst();
        JsonNode pod = YAML.readTree(overlay.at("/patches/0/patch").asString()).at("/spec/template/spec");
        JsonNode ops = YAML.readTree(overlay.at("/patches/1/patch").asString());

        assertThat(overlay.path("resources").toString()).isEqualTo("[\"../importer\"]");
        assertThat(pod.at("/containers/0/env/0/name").asString()).isEqualTo("IMPORT_ATTACHMENTS_CLAMDSTREAMMAX");
        assertThat(pod.at("/containers/0/env/0/$patch").asString()).isEqualTo("delete");
        assertThat(pod.at("/containers/0/volumeMounts/0/mountPath").asString()).isEqualTo("/etc/trailkeep/clamav-ca");
        assertThat(pod.at("/containers/0/volumeMounts/0/$patch").asString()).isEqualTo("delete");
        assertThat(pod.at("/volumes/0/name").asString()).isEqualTo("clamav-ca");
        assertThat(pod.at("/volumes/0/$patch").asString()).isEqualTo("delete");
        assertThat(overlay.at("/patches/1/target/name").asString()).isEqualTo("trailkeep-importer");
        assertThat(ops.toString())
                .isEqualTo("[{\"op\":\"test\",\"path\":\"/spec/egress/2/to/0/podSelector/matchLabels/"
                        + "app.kubernetes.io~1name\",\"value\":\"clamav\"},"
                        + "{\"op\":\"remove\",\"path\":\"/spec/egress/2\"}]");
        assertThat(env()).containsKey("IMPORT_ATTACHMENTS_CLAMDSTREAMMAX");
        assertThat(mountOf("clamav-ca").path("mountPath").asString()).isEqualTo("/etc/trailkeep/clamav-ca");
    }

    private static JsonNode job() throws IOException {
        List<JsonNode> documents = documents(JOB);
        assertThat(documents).hasSize(1);
        return documents.getFirst();
    }

    private static JsonNode pod() throws IOException {
        return job().at("/spec/template/spec");
    }

    private static Map<String, String> env() throws IOException {
        Map<String, String> env = new TreeMap<>();
        pod().at("/containers/0/env")
                .forEach(entry -> env.put(
                        entry.path("name").asString(), entry.path("value").asString()));
        return env;
    }

    private static JsonNode mountOf(String volume) throws IOException {
        for (JsonNode mount : pod().at("/containers/0/volumeMounts")) {
            if (volume.equals(mount.path("name").asString())) {
                return mount;
            }
        }
        throw new AssertionError("No mount of " + volume);
    }

    private static JsonNode mountAt(String path) throws IOException {
        for (JsonNode mount : pod().at("/containers/0/volumeMounts")) {
            if (path.equals(mount.path("mountPath").asString())) {
                return mount;
            }
        }
        throw new AssertionError("Nothing mounted at " + path);
    }

    private static JsonNode volume(String name) throws IOException {
        for (JsonNode volume : pod().get("volumes")) {
            if (name.equals(volume.path("name").asString())) {
                return volume;
            }
        }
        throw new AssertionError("No volume " + name);
    }

    private static Set<Integer> ports(JsonNode policy) {
        Set<Integer> ports = new TreeSet<>();
        policy.path("egress")
                .forEach(rule -> rule.path("ports")
                        .forEach(port -> ports.add(port.path("port").asInt())));
        return ports;
    }

    private static List<JsonNode> documents(Path file) throws IOException {
        return YAML.readerFor(JsonNode.class)
                .<JsonNode>readValues(Files.readString(file))
                .readAll();
    }
}
