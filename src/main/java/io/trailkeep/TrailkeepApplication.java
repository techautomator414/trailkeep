// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.modulith.Modulithic;

/** Entry point for the Trailkeep application. */
@SpringBootApplication
@ConfigurationPropertiesScan
@Modulithic(sharedModules = "shared")
public class TrailkeepApplication {

    /**
     * Starts the application.
     *
     * @param args command-line arguments passed to Spring Boot
     */
    public static void main(String[] args) {
        ConfigurableApplicationContext context = SpringApplication.run(TrailkeepApplication.class, args);
        // The purge batch (WP-24 part c), the importer Job (WP-26) and the migration Job have done their work once
        // start-up returns; they end there, with the context's exit code, whatever pooled threads the context holds.
        if (context.getEnvironment().matchesProfiles("purge | importer | migrate")) {
            System.exit(SpringApplication.exit(context));
        }
    }
}
