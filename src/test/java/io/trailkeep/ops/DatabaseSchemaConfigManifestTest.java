// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.ops;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * {@code APP_DB_SCHEMA} (issue #204): since #178 every runtime user on Oracle needs {@code app.db.schema} to name the
 * table owner, so the shared ConfigMap must carry it, the README must name it, and every workload that connects to the
 * database must read the ConfigMap whole and not override the key.
 */
class DatabaseSchemaConfigManifestTest {

    private static final YAMLMapper YAML = YAMLMapper.builder().build();

    private static final String KEY = "APP_DB_SCHEMA";

    @Test
    void theSharedConfigMapCarriesTheSchemaAndTheReadmeNamesItAsRequired() throws IOException {
        JsonNode config = document(Path.of("deploy/base/configmap.yaml"));

        assertThat(config.at("/metadata/name").asString()).isEqualTo("trailkeep-config");
        assertThat(config.at("/data").has(KEY)).as("the placeholder is present").isTrue();
        assertThat(Files.readString(Path.of("deploy/README.md")))
                .as("the overlay list names it")
                .contains("**`" + KEY + "`**");
    }

    @Test
    void theWebDeploymentThePurgeCronJobAndTheImporterJobAllReceiveItFromTheConfigMap() throws IOException {
        Map<String, String> workloads = new TreeMap<>();
        workloads.put("deploy/base/deployment.yaml", "/spec/template/spec");
        workloads.put("deploy/base/purge-cronjob.yaml", "/spec/jobTemplate/spec/template/spec");
        workloads.put("deploy/jobs/importer/importer-job.yaml", "/spec/template/spec");

        workloads.forEach((file, podPath) -> {
            try {
                JsonNode container = document(Path.of(file)).at(podPath).at("/containers/0");

                assertThat(container.at("/envFrom/0/configMapRef/name").asString())
                        .as(file + " reads the whole ConfigMap")
                        .isEqualTo("trailkeep-config");
                container
                        .path("env")
                        .forEach(entry -> assertThat(entry.path("name").asString())
                                .as(file + " does not override the key")
                                .isNotEqualTo(KEY));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    private static JsonNode document(Path file) throws IOException {
        return YAML.readTree(Files.readString(file));
    }
}
