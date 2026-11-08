// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.retention;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.context.WebApplicationContext;

/**
 * The {@code purge} profile's application (WP-24 part c), as the retention {@code CronJob} starts it: no web
 * application, no scheduler (the web jobs hold no grants under the purge credential; {@code TrailkeepApplication}
 * ends the process once the purge is done), and the purge runner, which ran at start-up. The schema is migrated here
 * because this test may be the first to touch the container; the profile itself turns migration and validation off.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles({"test", "purge"})
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = "spring.flyway.enabled=true")
class PurgeProfileIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void thePurgeProfileStartsNoWebApplicationAndNoScheduledJobButThePurgeRunner() {
        assertThat(context).isNotInstanceOf(WebApplicationContext.class);
        assertThat(context.getBeanNamesForType(ScheduledAnnotationBeanPostProcessor.class))
                .as("no scheduler, so no web job runs under the purge credential")
                .isEmpty();
        assertThat(context.getBeanNamesForType(ClientRegistrationRepository.class))
                .as("no OIDC client, so the purge pod needs no client secret")
                .isEmpty();
        assertThat(context.getBeanNamesForType(PurgeRunner.class)).hasSize(1);
        assertThat(context.getBeanNamesForType(ArchiveJob.class)).hasSize(1);
    }
}
