// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.InstantSource;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;

/**
 * The dispatcher's policy without a database or a mail server: the backoff doubles from one minute, is capped at four
 * hours and is spread by row; with mail disabled a pass touches nothing; enabled mail without a server, or without a
 * sender address, fails startup.
 */
class OutboxDispatcherTest {

    private final OutboxJdbcDao outbox = mock(OutboxJdbcDao.class);

    @Test
    void backoffDoublesFromOneMinuteIsCappedAtFourHoursAndAddsUpToOneQuarter() {
        long[] expectedMinutes = {1, 2, 4, 8, 16, 32, 64, 128, 240, 240};
        for (int attempts = 1; attempts <= expectedMinutes.length; attempts++) {
            Duration base = Duration.ofMinutes(expectedMinutes[attempts - 1]);
            for (long id = 1; id <= 50; id++) {
                assertThat(OutboxDispatcher.backoff(id, attempts))
                        .isGreaterThanOrEqualTo(base)
                        .isLessThan(base.plus(base.dividedBy(4)));
            }
        }
    }

    @Test
    void rowsThatFailedTogetherRetryApart() {
        assertThat(LongStream.rangeClosed(1, 20)
                        .mapToObj(id -> OutboxDispatcher.backoff(id, 3))
                        .distinct()
                        .count())
                .isGreaterThan(15);
    }

    @Test
    void disabledMailSendsNothingAndLeavesTheOutboxAlone() {
        OutboxDispatcher dispatcher = dispatcher(new MailProperties(false, null, Duration.ofMinutes(5)));

        assertThat(dispatcher.dispatch()).isZero();
        verifyNoInteractions(outbox);
    }

    @Test
    void enabledMailWithoutMailServerFailsStartup() {
        MailProperties enabled = new MailProperties(true, "trailkeep@example.invalid", Duration.ofMinutes(5));

        assertThatThrownBy(() -> dispatcher(enabled))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.mail.host");
    }

    @Test
    void enabledMailNeedsWellFormedSenderAndPositiveLease() {
        assertThatThrownBy(() -> new MailProperties(true, null, Duration.ofMinutes(5)))
                .hasMessageContaining("app.mail.from is required");
        assertThatThrownBy(() -> new MailProperties(true, " ", Duration.ofMinutes(5)))
                .hasMessageContaining("app.mail.from is required");
        assertThatThrownBy(() -> new MailProperties(true, "not an address", Duration.ofMinutes(5)))
                .hasMessageContaining("not an email address");
        assertThatThrownBy(() -> new MailProperties(false, null, Duration.ZERO)).hasMessageContaining("app.mail.lease");
        assertThat(new MailProperties(true, "Trailkeep <trailkeep@example.invalid>", Duration.ofMinutes(5)).from())
                .isEqualTo("Trailkeep <trailkeep@example.invalid>");
    }

    /** A dispatcher with no mail server bean, as when {@code spring.mail.host} is unset. */
    private OutboxDispatcher dispatcher(MailProperties mail) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory();
        InstantSource clock = InstantSource.system();
        return new OutboxDispatcher(
                outbox,
                beans.getBeanProvider(JavaMailSender.class),
                mail,
                new OutboxMetrics(new SimpleMeterRegistry(), outbox, clock),
                clock);
    }
}
