// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.orm.jpa.persistenceunit.ManagedClassNameFilter;

/**
 * Keeps the staging entities ({@value #STAGING_PACKAGE}) out of every persistence unit but the {@code importer}
 * profile's (design §2.1, R3). Hibernate validates the schema at start-up, and on Oracle it sees only the tables the
 * connecting user holds a privilege on; the runtime user holds none on staging, so the web application must not map
 * it. The importer maps everything, and the import user may read every mapped table (R__grants.sql).
 */
@Configuration(proxyBeanMethods = false)
class StagingEntityFilter {

    /** The package the staging entities live in, with its trailing dot. */
    static final String STAGING_PACKAGE = "io.trailkeep.importer.staging.";

    /**
     * Everything but the staging entities, outside the importer.
     *
     * @return the filter Spring Boot's entity scan applies
     */
    @Bean
    @Profile("!importer")
    ManagedClassNameFilter withoutStagingEntities() {
        return className -> !className.startsWith(STAGING_PACKAGE);
    }
}
