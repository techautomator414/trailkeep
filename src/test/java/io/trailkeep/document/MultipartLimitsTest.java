// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * {@code spring.servlet.multipart.max-request-size} must leave 1 MiB of headroom over
 * {@code app.attachments.max-bytes}: {@code max-file-size} follows {@code max-bytes}, the request limit does not.
 */
class MultipartLimitsTest {

    private static final String REQUEST_SIZE = "spring.servlet.multipart.max-request-size";
    private static final String MAX_BYTES = "app.attachments.max-bytes";

    /** The shipped {@code application.yml} supplies the defaults under test. */
    private final ApplicationContextRunner shipped = new ApplicationContextRunner()
            .withInitializer(context -> shippedYaml(context.getEnvironment()))
            .withUserConfiguration(AttachmentConfig.class)
            .withPropertyValues("app.attachments.temp-dir=${java.io.tmpdir}", "app.attachments.scanning=external");

    private static void shippedYaml(ConfigurableEnvironment environment) {
        try {
            new YamlPropertySourceLoader()
                    .load("application", new ClassPathResource("application.yml"))
                    .forEach(environment.getPropertySources()::addLast);
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    @Test
    void theShippedDefaultPairStarts() {
        shipped.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(AttachmentProperties.class).maxBytes().toBytes())
                    .isEqualTo(25L * 1024 * 1024);
        });
    }

    @Test
    void maximumAboveRequestLimitFailsStartupNamingBothPropertiesAndValues() {
        shipped.withPropertyValues(MAX_BYTES + "=30MB")
                .run(context -> assertThat(context)
                        .getFailure()
                        .hasStackTraceContaining(MAX_BYTES + " is " + 30L * 1024 * 1024)
                        .hasStackTraceContaining(REQUEST_SIZE + " is " + 26L * 1024 * 1024));
    }

    @Test
    void theHeadroomIsRequiredNotJustTheFileSize() {
        // 26 MB for the file leaves no room for the form around it in a 26 MB request.
        shipped.withPropertyValues(MAX_BYTES + "=26MB")
                .run(context -> assertThat(context).hasFailed());
        shipped.withPropertyValues(MAX_BYTES + "=26MB", REQUEST_SIZE + "=27MB")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void lowerMaximumStarts() {
        shipped.withPropertyValues(MAX_BYTES + "=5MB")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void unlimitedRequestLimitStarts() {
        shipped.withPropertyValues(MAX_BYTES + "=100MB", REQUEST_SIZE + "=-1")
                .run(context -> assertThat(context).hasNotFailed());
    }
}
