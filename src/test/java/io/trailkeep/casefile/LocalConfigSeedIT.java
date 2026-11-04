// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/** The {@code local} seed in {@code application-local.yml} binds, validates, loads and is idempotent. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class LocalConfigSeedIT {

    @Autowired
    private CaseConfigService config;

    @Autowired
    private AuditTrailService trail;

    @Test
    void theLocalSeedLoadsOnceAndIsAuditedAsTheSystem() throws Exception {
        LocalConfigSeed seed = new LocalConfigSeed(localSeed(), config);
        int typesBefore = config.caseTypes(DefaultCaseConfigService.SYSTEM).size();

        seed.run(new DefaultApplicationArguments());
        seed.run(new DefaultApplicationArguments());

        assertThat(config.caseTypes(DefaultCaseConfigService.SYSTEM))
                .hasSize(typesBefore + 3)
                .anySatisfy(
                        t -> assertThat(t.values().outcomes()).containsExactly("Resolved", "Transferred", "Duplicate"));
        assertThat(config.policies(DefaultCaseConfigService.SYSTEM))
                .extracting(p -> p.values().name())
                .contains("Standard 30 days", "Urgent 5 days");
        assertThat(trail.recent(CaseScope.system(), 20))
                .filteredOn(e -> e.origin() == AuditOrigin.SYSTEM)
                .hasSize(5)
                .allSatisfy(e -> assertThat(e.actorId()).isNull())
                .extracting(AuditRecord::action)
                .containsOnly("SLA_POLICY_CREATED", "CASE_TYPE_CREATED");
    }

    private static LocalConfigSeed.Seed localSeed() throws Exception {
        StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader()
                .load("local", new ClassPathResource("application-local.yml"))
                .forEach(environment.getPropertySources()::addLast);
        return new Binder(ConfigurationPropertySources.get(environment))
                .bind("app.seed", LocalConfigSeed.Seed.class)
                .get();
    }
}
