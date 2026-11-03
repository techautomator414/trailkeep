// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
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
        SpringApplication.run(TrailkeepApplication.class, args);
    }
}
