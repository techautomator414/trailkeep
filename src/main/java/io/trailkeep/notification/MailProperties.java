// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Outgoing mail under {@code app.mail} (BUILD_PLAN §6 "Outbox dispatcher"). The mail server itself is Spring Boot's
 * {@code spring.mail.*} (host, port, credentials from a mounted secret, STARTTLS in {@code prod}).
 *
 * @param enabled whether the dispatcher sends; {@code false} stops sending while mail keeps queuing in the outbox, to
 *     be sent once it is {@code true} again (for example while the mail relay is being replaced)
 * @param from the {@code From} address, such as {@code Trailkeep <trailkeep@example.org>}; required while enabled
 * @param lease how long a claim holds its rows before the reaper may hand them to another dispatcher; longer than one
 *     batch can take (50 messages over one connection, with the SMTP timeouts in {@code application.yml})
 */
@ConfigurationProperties(prefix = "app.mail")
record MailProperties(
        @DefaultValue("true") boolean enabled,
        @Nullable String from,
        @DefaultValue("5m") Duration lease) {

    /**
     * Fails startup on a missing or malformed sender while enabled, and on a lease that is not positive.
     *
     * @param enabled whether the dispatcher sends
     * @param from the {@code From} address
     * @param lease the claim's lease
     */
    MailProperties {
        if (enabled) {
            if (from == null || from.isBlank()) {
                throw new IllegalArgumentException("app.mail.from is required while app.mail.enabled is true");
            }
            try {
                new InternetAddress(from, true).validate();
            } catch (AddressException malformed) {
                throw new IllegalArgumentException("app.mail.from is not an email address", malformed);
            }
        }
        if (lease.isNegative() || lease.isZero()) {
            throw new IllegalArgumentException("app.mail.lease must be positive");
        }
    }
}
