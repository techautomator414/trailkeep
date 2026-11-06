// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Seeds synthetic SLA policies and case types for the {@code local} profile from {@code app.seed} in
 * {@code application-local.yml}. Inserts only what is missing by name, so restarts keep local edits. Names are
 * compared stripped, as the service stores them.
 */
@Component
@Profile("local")
class LocalConfigSeed implements ApplicationRunner {

    /**
     * The seed.
     *
     * @param slaPolicies policies to create
     * @param caseTypes case types to create, naming their policy
     */
    @ConfigurationProperties(prefix = "app.seed")
    record Seed(
            @DefaultValue List<SlaPolicyCommand> slaPolicies,
            @DefaultValue List<CaseTypeSeed> caseTypes) {}

    /**
     * A seeded case type.
     *
     * @param name the name
     * @param slaPolicy the name of its SLA policy
     * @param retentionYears retention after closure
     * @param outcomes allowed outcomes
     */
    record CaseTypeSeed(String name, String slaPolicy, int retentionYears, List<String> outcomes) {}

    private final Seed seed;
    private final CaseConfigService config;

    LocalConfigSeed(Seed seed, CaseConfigService config) {
        this.seed = seed;
        this.config = config;
    }

    @Override
    public void run(ApplicationArguments args) {
        CaseConfigService.Actor system = DefaultCaseConfigService.SYSTEM;
        Map<String, Long> policies = config.policies(system).stream()
                .collect(Collectors.toMap(p -> p.values().name(), CaseConfigService.SlaPolicyView::id));
        seed.slaPolicies().stream()
                .filter(p -> !policies.containsKey(p.name().strip()))
                .forEach(p -> policies.put(p.name().strip(), config.savePolicy(system, null, p)));
        List<String> existing =
                config.caseTypes(system).stream().map(t -> t.values().name()).toList();
        seed.caseTypes().stream()
                .filter(t -> !existing.contains(t.name().strip()))
                .forEach(t -> config.saveCaseType(
                        system,
                        null,
                        new CaseTypeCommand(t.name(), policyId(policies, t), t.retentionYears(), true, t.outcomes())));
    }

    private static long policyId(Map<String, Long> policies, CaseTypeSeed caseType) {
        Long id = policies.get(caseType.slaPolicy().strip());
        if (id == null) {
            throw new IllegalStateException("app.seed: case type '" + caseType.name() + "' names SLA policy '"
                    + caseType.slaPolicy() + "', which is neither seeded nor already present");
        }
        return id;
    }
}
