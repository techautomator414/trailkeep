// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.notification.OutboxJdbcDao.Claimed;
import io.trailkeep.notification.OutboxJdbcDao.Outcome;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Sends the outbox, the only component that talks to a mail server (BUILD_PLAN §6 "Outbox dispatcher"). Every minute,
 * on every replica, it returns lapsed claims to pending ({@link OutboxJdbcDao#reap}), then claims due rows 50 at a
 * time and hands each batch to the mail server over one connection, as multipart text and HTML, with <strong>no
 * transaction open</strong>: the claim committed before the send, and each row's outcome is recorded afterwards in a
 * transaction of its own. A sent row becomes {@code SENT}; a failed one is retried after 1, 2, 4 … minutes (capped at
 * four hours, with up to a quarter more so rows that failed together do not retry together) and becomes {@code FAILED}
 * at its {@value #MAX_ATTEMPTS}th failure, keeping the error for the System screen.
 *
 * <p><strong>No ShedLock.</strong> Replicas need no lock between them: the claim's {@code FOR UPDATE SKIP LOCKED}
 * gives each row to exactly one claimer, and the lease keeps it there until it is released or the lease lapses.
 *
 * <p><strong>At-least-once.</strong> A replica that dies after the mail server accepted a batch but before the rows
 * were released leaves them {@code SENDING}; once the lease lapses the reaper returns them and they are sent again.
 * Nothing is lost; that one batch may arrive twice (IMPLEMENTATION_PLAN A.1 #14).
 *
 * <p>With {@code app.mail.enabled=false} a tick does nothing and mail keeps queuing. A pass stops after a batch in
 * which nothing was sent, so an unreachable server costs one batch's attempts per minute, not the whole backlog's.
 */
@Component
final class OutboxDispatcher {

    /** Attempts after which a row is {@code FAILED}. */
    static final int MAX_ATTEMPTS = 10;

    private static final Duration MAX_BACKOFF = Duration.ofHours(4);
    private static final Logger LOG = LoggerFactory.getLogger(OutboxDispatcher.class);

    private final OutboxJdbcDao outbox;
    private final @Nullable JavaMailSender sender;
    private final MailProperties mail;
    private final OutboxMetrics metrics;
    private final InstantSource clock;

    OutboxDispatcher(
            OutboxJdbcDao outbox,
            ObjectProvider<JavaMailSender> sender,
            MailProperties mail,
            OutboxMetrics metrics,
            InstantSource clock) {
        this.outbox = outbox;
        this.sender = sender.getIfAvailable();
        this.mail = mail;
        this.metrics = metrics;
        this.clock = clock;
        if (mail.enabled() && this.sender == null) {
            throw new IllegalStateException("app.mail.enabled is true but no mail server is set (spring.mail.host)");
        }
    }

    /** The scheduled pass; {@code app.mail.dispatch-cron} is {@code -} in tests, which call {@link #dispatch}. */
    @Scheduled(cron = "${app.mail.dispatch-cron:0 * * * * *}")
    void tick() {
        dispatch();
    }

    /**
     * One pass: reap, then claim and send batches until the outbox has no due rows or a batch sent nothing.
     *
     * @return how many mails were sent
     */
    int dispatch() {
        JavaMailSender server = sender;
        if (!mail.enabled() || server == null) {
            return 0;
        }
        int reaped = outbox.reap(clock.instant());
        if (reaped > 0) {
            LOG.warn("{} mail(s) whose claim lapsed returned to pending; they may be sent twice", reaped);
        }
        int total = 0;
        int sent;
        List<Claimed> batch;
        do {
            Instant now = clock.instant();
            batch = outbox.claim(now, now.plus(mail.lease()), OutboxJdbcDao.MAX_CLAIM);
            sent = send(server, batch);
            total += sent;
        } while (batch.size() == OutboxJdbcDao.MAX_CLAIM && sent > 0);
        return total;
    }

    private int send(JavaMailSender server, List<Claimed> batch) {
        Map<MimeMessage, Claimed> messages = new LinkedHashMap<>();
        Map<Object, Exception> failures = new HashMap<>();
        for (Claimed row : batch) {
            MimeMessage message = server.createMimeMessage();
            try {
                compose(message, row);
            } catch (MessagingException malformed) {
                failures.put(message, malformed);
            }
            messages.put(message, row);
        }
        List<MimeMessage> ready = messages.keySet().stream()
                .filter(message -> !failures.containsKey(message))
                .toList();
        try {
            if (!ready.isEmpty()) {
                server.send(ready.toArray(MimeMessage[]::new));
            }
        } catch (MailSendException partly) {
            // Keyed by the message objects passed in; all of them when the connection itself failed.
            failures.putAll(partly.getFailedMessages());
        } catch (MailException wholly) {
            ready.forEach(message -> failures.put(message, wholly));
        }
        int sent = 0;
        for (Map.Entry<MimeMessage, Claimed> entry : messages.entrySet()) {
            Exception failure = failures.get(entry.getKey());
            if (failure == null) {
                release(entry.getValue(), Outcome.SENT, null, null);
                sent++;
            } else {
                failed(entry.getValue(), failure);
            }
        }
        return sent;
    }

    private void compose(MimeMessage message, Claimed row) throws MessagingException {
        MimeMessageHelper helper = new MimeMessageHelper(message, true, StandardCharsets.UTF_8.name());
        helper.setFrom(from());
        helper.setTo(row.recipient());
        helper.setSubject(row.subject());
        helper.setText(row.text(), row.html());
    }

    private InternetAddress from() throws AddressException {
        String from = mail.from();
        if (from == null) {
            throw new AddressException("app.mail.from is not set");
        }
        return new InternetAddress(from, true);
    }

    private void failed(Claimed row, Exception failure) {
        int attempts = row.attempts() + 1;
        Instant now = clock.instant();
        // The error text may name the recipient: it is kept on the row, never logged (Part B "Logging").
        String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
        if (attempts >= MAX_ATTEMPTS) {
            LOG.warn(
                    "Mail {} failed for the last time ({})",
                    row.id(),
                    failure.getClass().getSimpleName());
            release(row, Outcome.FAILED, null, error);
        } else {
            LOG.info(
                    "Mail {} attempt {} failed ({})",
                    row.id(),
                    attempts,
                    failure.getClass().getSimpleName());
            release(row, Outcome.RETRY, now.plus(backoff(row.id(), attempts)), error);
        }
    }

    private void release(Claimed row, Outcome outcome, @Nullable Instant nextAttemptAt, @Nullable String error) {
        if (outbox.release(row.id(), outcome, clock.instant(), nextAttemptAt, error)) {
            metrics.attempted(outcome);
        } else {
            LOG.warn("Mail {} was no longer this dispatcher's when its attempt ended", row.id());
        }
    }

    /**
     * The wait before the next attempt: 1, 2, 4 … minutes after the first, second, third failure, capped at four
     * hours, plus up to a quarter more, spread by the row's id so rows that failed together retry apart.
     *
     * @param id the row
     * @param attempts the attempts made so far, at least 1
     * @return the wait
     */
    static Duration backoff(long id, int attempts) {
        long minutes = Math.min(1L << Math.min(Math.max(attempts, 1) - 1, 30), MAX_BACKOFF.toMinutes());
        long spread = Math.floorMod(id * 0x9E3779B97F4A7C15L + attempts, 1000L);
        return Duration.ofMinutes(minutes).plusMillis(minutes * 60_000L * spread / 4000L);
    }
}
