/*
 * SPDX-License-Identifier: Apache-2.0
 */
package io.trailkeep;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point of the Trailkeep application. */
@SpringBootApplication
public class TrailkeepApplication {

    public static void main(String[] args) {
        SpringApplication.run(TrailkeepApplication.class, args);
    }
}
