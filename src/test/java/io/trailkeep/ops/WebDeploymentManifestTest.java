// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static io.trailkeep.ops.ManifestSupport.document;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * The web Deployment's secrets (TEST_MAP gap 15, #246): the database login, the SMTP password, the OIDC client secret
 * and the audit key all reach the pod as files of the one {@code trailkeep-web} Secret (Spring {@code configtree}),
 * never as environment literals, and none of the purge, schema-owner or importer credentials is mounted. No Oracle
 * wallet is mounted today (the JDBC URL is {@code tcps}); the volume-type check below is what a wallet would have to
 * pass, a Secret volume.
 */
class WebDeploymentManifestTest {

    private static final Path BASE = Path.of("deploy/base");

    private static final Pattern SECRET_LIKE_KEY =
            Pattern.compile("(?i).*(PASSWORD|SECRET|TOKEN|HMAC|PRIVATE|WALLET|CREDENTIAL|API_?KEY|_KEY$).*");

    @Test
    void secretsComeFromOneMountedSecretAndTheCaCertificateNeverFromOtherWorkloadsSecrets() throws IOException {
        Map<String, String> secretVolumes = new TreeMap<>();
        for (JsonNode volume : pod().get("volumes")) {
            if (volume.has("secret")) {
                secretVolumes.put(
                        volume.path("name").asString(),
                        volume.path("secret").path("secretName").asString());
            } else {
                assertThat(volume.has("emptyDir"))
                        .as("volume %s: only emptyDir scratch space or a Secret (a wallet would be a Secret)", volume)
                        .isTrue();
            }
        }

        assertThat(secretVolumes).isEqualTo(Map.of("secrets", "trailkeep-web", "clamav-ca", "clamav-ca"));
        assertThat(pod().toString())
                .as("the purge, schema-owner and importer credentials are never mounted into the web pods")
                .doesNotContain("trailkeep-purge", "trailkeep-migrate", "trailkeep-import");
        JsonNode mount = mountAt("/etc/trailkeep/secrets");
        assertThat(mount.path("name").asString()).isEqualTo("secrets");
        assertThat(mount.path("readOnly").asBoolean()).isTrue();
        assertThat(mountAt("/etc/trailkeep/clamav-ca").path("readOnly").asBoolean())
                .isTrue();
    }

    @Test
    void theContainerHasNoEnvironmentLiteralsAndNoSecretReferencesInTheEnvironment() throws IOException {
        JsonNode container = container();

        assertThat(container.has("env"))
                .as("no env entry at all: a literal secret, or a secretKeyRef, would be a way around the files")
                .isFalse();
        assertThat(container.path("envFrom")).hasSize(1);
        assertThat(container.at("/envFrom/0/configMapRef/name").asString()).isEqualTo("trailkeep-config");
        assertThat(container.at("/envFrom/0").has("secretRef")).isFalse();
    }

    @Test
    void theConfigMapHoldsNoSecretAndPointsConfigtreeAtTheMountedDirectory() throws IOException {
        JsonNode data = document(BASE.resolve("configmap.yaml"), "ConfigMap", "trailkeep-config")
                .path("data");

        assertThat(data.path("SPRING_CONFIG_IMPORT").asString()).isEqualTo("configtree:/etc/trailkeep/secrets/");
        data.properties().forEach(entry -> {
            assertThat(SECRET_LIKE_KEY.matcher(entry.getKey()).matches())
                    .as("ConfigMap key %s looks like a secret", entry.getKey())
                    .isFalse();
            assertThat(entry.getValue().asString())
                    .as("ConfigMap value of %s embeds a login", entry.getKey())
                    .doesNotContainPattern("://[^/@\\s]+:[^/@\\s]+@")
                    .doesNotContain("password=", "pwd=");
        });
        assertThat(List.of("SPRING_DATASOURCE_PASSWORD", "SPRING_MAIL_PASSWORD", "APP_AUDIT_HMAC_KEY"))
                .allSatisfy(key -> assertThat(data.has(key)).isFalse());
    }

    private static JsonNode pod() throws IOException {
        return document(BASE.resolve("deployment.yaml"), "Deployment", "trailkeep")
                .at("/spec/template/spec");
    }

    private static JsonNode container() throws IOException {
        return pod().at("/containers/0");
    }

    private static JsonNode mountAt(String path) throws IOException {
        for (JsonNode mount : container().path("volumeMounts")) {
            if (path.equals(mount.path("mountPath").asString())) {
                return mount;
            }
        }
        throw new AssertionError("Nothing mounted at " + path);
    }
}
