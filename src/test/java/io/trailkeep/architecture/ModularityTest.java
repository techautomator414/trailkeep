// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.TrailkeepApplication;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

class ModularityTest {

    private static final ApplicationModules MODULES = ApplicationModules.of(TrailkeepApplication.class);

    @Test
    void moduleBoundariesHold() {
        MODULES.verify();
    }

    @Test
    void everyPlannedModuleIsDetected() {
        assertThat(MODULES.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .containsExactlyInAnyOrder(
                        "shared", "security", "audit", "user", "casefile", "closure", "updaterequest", "sla",
                        "document", "notification", "report", "retention", "importer", "web");
    }

    @Test
    void sharedIsAnOpenModule() {
        assertThat(MODULES.getModuleByName("shared"))
                .hasValueSatisfying(module -> assertThat(module.isOpen()).isTrue());
    }

    @Test
    void writesModuleDocumentation() {
        new Documenter(MODULES).writeDocumentation();

        assertThat(Path.of("target", "spring-modulith-docs")).isDirectory();
        assertThat(Files.exists(Path.of("target", "spring-modulith-docs", "components.puml"))).isTrue();
    }
}
