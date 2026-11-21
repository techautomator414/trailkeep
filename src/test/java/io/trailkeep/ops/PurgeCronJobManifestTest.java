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
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The retention purge CronJob (BUILD_PLAN §9 "Separation of duties", TEST_MAP gap 14, #246): it runs under its own
 * {@code TRAILKEEP_PURGE} credential, in the {@code purge} profile, and never sees the web application's, the schema
 * owner's or the importer's. kube-linter and the kustomize renders run on {@code main} only (Part G), so the
 * properties that keep the credentials apart are checked here, on every build.
 */
class PurgeCronJobManifestTest {

    private static final Path BASE = Path.of("deploy/base");

    private static final Path CRONJOB = BASE.resolve("purge-cronjob.yaml");

    private static final List<String> OTHER_CREDENTIAL_SECRETS = List.of(
            "trailkeep-web",
            "trailkeep-migrate",
            "trailkeep-import",
            "trailkeep-import-source",
            "trailkeep-import-hash-key");

    @Test
    void theOnlyCredentialItMountsIsTheWholePurgeSecretPlusTheCaCertificate() throws IOException {
        Map<String, String> secretVolumes = new TreeMap<>();
        for (JsonNode volume : pod().get("volumes")) {
            if (volume.has("secret")) {
                secretVolumes.put(
                        volume.path("name").asString(),
                        volume.path("secret").path("secretName").asString());
            } else {
                assertThat(volume.has("emptyDir"))
                        .as("volume %s is an emptyDir or a Secret, nothing else", volume.path("name"))
                        .isTrue();
            }
        }

        assertThat(secretVolumes)
                .as("the purge credential, and the CA certificate (no key) the shared application context loads")
                .isEqualTo(Map.of("secrets", "trailkeep-purge", "clamav-ca", "clamav-ca"));
        assertThat(pod().toString())
                .as("no other workload's credential appears anywhere in the pod spec")
                .doesNotContain(OTHER_CREDENTIAL_SECRETS.toArray(new String[0]));
        JsonNode mount = mountOf("secrets");
        assertThat(mount.path("mountPath").asString()).isEqualTo("/etc/trailkeep/secrets");
        assertThat(mount.path("readOnly").asBoolean()).isTrue();
    }

    @Test
    void noSecretIsInjectedThroughTheEnvironmentAndTheProfilesAreProdAndPurge() throws IOException {
        JsonNode container = container();

        Map<String, String> env = new TreeMap<>();
        for (JsonNode entry : container.path("env")) {
            assertThat(entry.has("valueFrom"))
                    .as("env %s takes a literal profile or switch, never a Secret reference", entry.path("name"))
                    .isFalse();
            env.put(entry.path("name").asString(), entry.path("value").asString());
        }
        assertThat(env)
                .as("the profile that selects the purge connection, and no web server; nothing else")
                .isEqualTo(Map.of(
                        "SPRING_PROFILES_ACTIVE", "prod,purge",
                        "SPRING_MAIN_WEB_APPLICATION_TYPE", "none"));
        assertThat(container.path("envFrom")).hasSize(1);
        assertThat(container.at("/envFrom/0/configMapRef/name").asString()).isEqualTo("trailkeep-config");
        assertThat(container.at("/envFrom/0").has("secretRef")).isFalse();
    }

    @Test
    void theConfigMapNamesThreeDistinctDatabaseUsersAndThePurgeSecretCarriesNoneOfThem() throws IOException {
        JsonNode data = document(BASE.resolve("configmap.yaml"), "ConfigMap", "trailkeep-config")
                .path("data");

        assertThat(data.path("APP_DB_PURGE_USER").asString()).isEqualTo("TRAILKEEP_PURGE");
        assertThat(List.of(
                        data.path("APP_DB_APP_USER").asString(),
                        data.path("APP_DB_PURGE_USER").asString(),
                        data.path("APP_DB_IMPORT_USER").asString()))
                .as("three separate database accounts")
                .doesNotHaveDuplicates()
                .doesNotContain("");
        assertThat(data.path("SPRING_CONFIG_IMPORT").asString())
                .as("secret values are files under the one mounted directory, so the purge Secret decides the login")
                .isEqualTo("configtree:/etc/trailkeep/secrets/");
        data.propertyNames()
                .forEach(key -> assertThat(key)
                        .as("the ConfigMap is shared by the purge pod and must not carry a login")
                        .doesNotStartWith("SPRING_DATASOURCE_"));
    }

    @Test
    void theJobIsHardenedLikeTheOtherJobsAndShipsSuspended() throws IOException {
        JsonNode cron = cronJob();
        JsonNode spec = pod();
        JsonNode context = container().path("securityContext");

        assertThat(cron.at("/spec/suspend").asBoolean()).as("shipped suspended").isTrue();
        assertThat(cron.at("/spec/concurrencyPolicy").asString()).isEqualTo("Forbid");
        assertThat(cron.at("/spec/jobTemplate/spec/activeDeadlineSeconds").asInt())
                .isPositive();
        assertThat(spec.path("restartPolicy").asString()).isEqualTo("Never");
        assertThat(spec.path("automountServiceAccountToken").asBoolean())
                .as("no service-account token in the pod")
                .isFalse();
        assertThat(spec.at("/securityContext/runAsNonRoot").asBoolean()).isTrue();
        assertThat(spec.at("/securityContext/seccompProfile/type").asString()).isEqualTo("RuntimeDefault");
        assertThat(context.path("allowPrivilegeEscalation").asBoolean()).isFalse();
        assertThat(context.path("readOnlyRootFilesystem").asBoolean()).isTrue();
        assertThat(context.path("runAsNonRoot").asBoolean()).isTrue();
        assertThat(context.at("/capabilities/drop").toString()).isEqualTo("[\"ALL\"]");
        assertThat(context.has("privileged")).isFalse();
        assertThat(spec.path("containers")).hasSize(1);
        assertThat(spec.has("hostNetwork") || spec.has("hostPID") || spec.has("hostIPC"))
                .isFalse();
        assertThat(container().path("volumeMounts").size()).isEqualTo(3);
        assertThat(mountOf("tmp").path("mountPath").asString()).isEqualTo("/tmp");
    }

    @Test
    void theNetworkPoliciesDenyIngressAndKeepThePodUnderTheBaseEgressRules() throws IOException {
        Map<String, String> labels = labels(cronJob().at("/spec/jobTemplate/spec/template"));
        Path file = BASE.resolve("networkpolicy.yaml");
        JsonNode defaultDeny = document(file, "NetworkPolicy", "trailkeep-default-deny");
        JsonNode webIngress = document(file, "NetworkPolicy", "trailkeep-web-ingress");
        JsonNode egress = document(file, "NetworkPolicy", "trailkeep-egress");

        assertThat(labels).containsEntry("app.kubernetes.io/component", "purge");
        assertThat(selects(defaultDeny, labels))
                .as("default-deny covers the purge pod, for both directions")
                .isTrue();
        assertThat(defaultDeny.at("/spec/policyTypes").toString()).isEqualTo("[\"Ingress\",\"Egress\"]");
        assertThat(selects(webIngress, labels))
                .as("nothing is allowed in: the only ingress rule is for the web pods")
                .isFalse();
        assertThat(selects(egress, labels))
                .as("the purge reaches the database through the base egress rules, which also cover DNS")
                .isTrue();
        documents(file).forEach(policy -> {
            if (selects(policy, labels)
                    && policy.at("/spec/policyTypes").toString().contains("Ingress")) {
                assertThat(policy.has("ingress"))
                        .as("%s admits no ingress to the purge pod", policy.at("/metadata/name"))
                        .isFalse();
            }
        });
    }

    private static JsonNode cronJob() throws IOException {
        return document(CRONJOB, "CronJob", "trailkeep-purge");
    }

    private static JsonNode pod() throws IOException {
        return cronJob().at("/spec/jobTemplate/spec/template/spec");
    }

    private static JsonNode container() throws IOException {
        return pod().at("/containers/0");
    }

    private static JsonNode mountOf(String volume) throws IOException {
        for (JsonNode mount : container().path("volumeMounts")) {
            if (volume.equals(mount.path("name").asString())) {
                return mount;
            }
        }
        throw new AssertionError("No mount of " + volume);
    }
}
