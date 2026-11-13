// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Where Flyway runs (issue #205, BUILD_PLAN §8 rule 7): the runtime users own nothing and hold no DDL right, so the
 * {@code prod} profile migrates nothing, and only the migration Job's {@code prod,migrate} turns Flyway back on. Reads
 * the real profile files; starts no database, no web server and no auto-configuration.
 */
class FlywayProfilesTest {

    @Test
    void prodAloneAndTheTwoBatchProfilesOfTheRuntimeUsersRunNoFlyway() {
        assertThat(flywayEnabled("prod")).as("web pods").isFalse();
        assertThat(flywayEnabled("prod", "purge")).as("purge CronJob").isFalse();
        assertThat(flywayEnabled("prod", "importer")).as("importer Job").isFalse();
    }

    @Test
    void theMigrateProfileAloneRunsFlywayUnderProd() {
        assertThat(flywayEnabled("prod", "migrate")).isTrue();
    }

    @Test
    void localAndTestProfilesStillMigrateOnStartUp() {
        assertThat(flywayEnabled("local"))
                .as("a developer's box has one owner user")
                .isTrue();
        assertThat(flywayEnabled("test")).isTrue();
    }

    @Test
    void theMigrationJobIsBatchWithoutWebServerAndHibernateOnlyValidates() {
        try (ConfigurableApplicationContext context = start("prod", "migrate")) {
            assertThat(context.getEnvironment().getProperty("spring.main.web-application-type"))
                    .isEqualTo("none");
            assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                    .as("Hibernate validates the migrated schema and never changes it")
                    .isEqualTo("validate");
        }
    }

    private static boolean flywayEnabled(String... profiles) {
        try (ConfigurableApplicationContext context = start(profiles)) {
            // Spring Boot's own default (enabled) when no profile file says otherwise.
            return context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class, Boolean.TRUE);
        }
    }

    private static ConfigurableApplicationContext start(String... profiles) {
        return new SpringApplicationBuilder(Empty.class)
                .web(WebApplicationType.NONE)
                .profiles(profiles)
                .bannerMode(Banner.Mode.OFF)
                .run();
    }

    /** No beans, no auto-configuration: only the environment is under test. */
    @Configuration(proxyBeanMethods = false)
    static class Empty {}
}
