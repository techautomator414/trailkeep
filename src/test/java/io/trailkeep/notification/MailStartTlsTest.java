// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.Banner;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.mail.javamail.JavaMailSenderImpl;

/**
 * Mail to the relay needs STARTTLS and a verified server identity in prod (BUILD_PLAN §9 Transport; security test map
 * gap 8): a relay that does not offer STARTTLS is refused, never used in the clear, and its certificate must name the
 * host. Asserted on the session properties of the {@link JavaMailSenderImpl} Spring Boot builds from the {@code prod}
 * profile, which is the sender {@code OutboxDispatcher} is given, not on the text of the profile file. Starts only the
 * mail sender's auto-configuration; nothing connects.
 */
class MailStartTlsTest {

    @Test
    void prodsMailSenderRequiresStartTls() {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(MailSenderOnly.class)
                .web(WebApplicationType.NONE)
                .profiles("prod")
                .bannerMode(Banner.Mode.OFF)
                // prod has no default relay (app.base-url, app.mail.from and spring.mail.host come from the ConfigMap).
                .run("--spring.mail.host=127.0.0.1")) {
            Properties session =
                    context.getBean(JavaMailSenderImpl.class).getSession().getProperties();
            assertThat(session.getProperty("mail.smtp.starttls.enable"))
                    .as("STARTTLS offered")
                    .isEqualTo("true");
            assertThat(session.getProperty("mail.smtp.starttls.required"))
                    .as("STARTTLS required")
                    .isEqualTo("true");
            assertThat(session.getProperty("mail.smtp.ssl.checkserveridentity"))
                    .as("relay certificate must match the host (JavaMail defaults to false)")
                    .isEqualTo("true");
        }
    }

    /** Spring Boot's mail sender auto-configuration and nothing else. */
    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(MailSenderAutoConfiguration.class)
    static class MailSenderOnly {}
}
