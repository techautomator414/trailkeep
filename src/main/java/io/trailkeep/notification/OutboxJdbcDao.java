// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The outbox's state machine (BUILD_PLAN §6 "Outbox dispatcher", IMPLEMENTATION_PLAN A.1 #14): the claim, the release
 * after an attempt and the reaper, each in a transaction of its own that commits before the caller does any I/O.
 *
 * <p>The claim is native SQL because JPA cannot express it safely. A row-limited {@code PESSIMISTIC_WRITE} query is
 * {@code FETCH FIRST … FOR UPDATE}, which Oracle refuses, and Hibernate's follow-on locking then locks rows one by
 * one and returns rows it failed to lock; JPA also has no {@code SKIP LOCKED}. So the claim takes two statements:
 * (1) up to {@link #MAX_CLAIM} candidate ids, oldest first, without a lock; (2) {@code … WHERE ID IN (:ids) AND
 * STATUS = 'PENDING' FOR UPDATE SKIP LOCKED}, which returns only the rows this transaction now holds (rows another
 * dispatcher holds are skipped, and rows it already moved to {@code SENDING} no longer match). Only those rows are
 * marked {@code SENDING} until the lease ends, and only they are returned. Both vendors run the same statements.
 *
 * <p>The release and the reaper are plain conditional updates, which JPA does express, so they are JPQL bulk updates
 * here rather than native SQL; they sit in this class so the whole state machine is in one place.
 */
@Repository
class OutboxJdbcDao {

    /** The most rows one claim takes; far under Oracle's 1000-element {@code IN} list limit. */
    static final int MAX_CLAIM = 50;

    private static final String CANDIDATES = "SELECT ID FROM NOTIFICATION_OUTBOX WHERE STATUS = 'PENDING'"
            + " AND NEXT_ATTEMPT_AT <= :now ORDER BY ID FETCH FIRST :limit ROWS ONLY";
    private static final String LOCK = "SELECT ID, RECIPIENT, TEMPLATE_ID, SUBJECT, BODY_TEXT, BODY_HTML, ATTEMPTS"
            + " FROM NOTIFICATION_OUTBOX WHERE ID IN (:ids) AND STATUS = 'PENDING' FOR UPDATE SKIP LOCKED";
    private static final String MARK_SENDING =
            "UPDATE NOTIFICATION_OUTBOX SET STATUS = 'SENDING', LOCKED_UNTIL = :until WHERE ID IN (:ids)";
    private static final String MARK_SENT = "UPDATE NotificationOutbox o SET o.status = :next, o.sentAt = :now,"
            + " o.lockedUntil = NULL, o.attempts = o.attempts + 1 WHERE o.id = :id AND o.status = :sending";
    private static final String MARK_FAILED = "UPDATE NotificationOutbox o SET o.status = :next,"
            + " o.nextAttemptAt = :due, o.lockedUntil = NULL, o.attempts = o.attempts + 1, o.lastError = :error"
            + " WHERE o.id = :id AND o.status = :sending";
    private static final String REAP = "UPDATE NotificationOutbox o SET o.status = :pending, o.lockedUntil = NULL,"
            + " o.nextAttemptAt = :now WHERE o.status = :sending AND o.lockedUntil < :now";
    private static final String COUNT = "SELECT COUNT(o) FROM NotificationOutbox o WHERE o.status = :status";
    private static final String OLDEST_PENDING =
            "SELECT MIN(o.createdAt) FROM NotificationOutbox o WHERE o.status = :status";

    private final NamedParameterJdbcTemplate jdbc;
    private final EntityManager entityManager;
    private final TransactionTemplate ownTransaction;

    OutboxJdbcDao(
            NamedParameterJdbcTemplate jdbc, EntityManager entityManager, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.entityManager = entityManager;
        this.ownTransaction = new TransactionTemplate(transactions);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A claimed row, everything the dispatcher needs to send it.
     *
     * @param id the row
     * @param recipient the address
     * @param templateId the template it was rendered from
     * @param subject the subject line
     * @param text the plain-text body
     * @param html the HTML body
     * @param attempts the attempts made before this one
     */
    record Claimed(
            long id, String recipient, String templateId, String subject, String text, String html, int attempts) {

        /** Never the address or the bodies (Part B "Logging"). */
        @Override
        public String toString() {
            return "Claimed[id=" + id + ", template=" + templateId + ", attempts=" + attempts + "]";
        }
    }

    /** What an attempt ended in. */
    enum Outcome {
        /** Handed to the mail server: the row becomes {@code SENT}. */
        SENT,
        /** Failed, with attempts left: the row becomes {@code PENDING} again, due at the next attempt. */
        RETRY,
        /** Failed for the last time: the row becomes {@code FAILED}. */
        FAILED
    }

    /**
     * Claims due rows for one dispatcher and commits, so the caller sends with no transaction open.
     *
     * @param now the current time; rows due at or before it are candidates
     * @param lockedUntil when the claim lapses and the reaper may hand the rows to another dispatcher
     * @param limit the most rows to claim, 1 to {@link #MAX_CLAIM}
     * @return the rows this claim holds, possibly fewer than the candidates, never one another claim holds
     */
    List<Claimed> claim(Instant now, Instant lockedUntil, int limit) {
        if (limit < 1 || limit > MAX_CLAIM) {
            throw new IllegalArgumentException("a claim takes 1 to " + MAX_CLAIM + " rows");
        }
        return Objects.requireNonNull(ownTransaction.execute(status -> {
            List<Long> candidates =
                    jdbc.queryForList(CANDIDATES, Map.of("now", timestamp(now), "limit", limit), Long.class);
            if (candidates.isEmpty()) {
                return List.<Claimed>of();
            }
            List<Claimed> held = jdbc.query(
                    LOCK,
                    Map.of("ids", candidates),
                    (row, index) -> new Claimed(
                            row.getLong("ID"),
                            row.getString("RECIPIENT"),
                            row.getString("TEMPLATE_ID"),
                            row.getString("SUBJECT"),
                            row.getString("BODY_TEXT"),
                            row.getString("BODY_HTML"),
                            row.getInt("ATTEMPTS")));
            if (!held.isEmpty()) {
                List<Long> ids = held.stream().map(Claimed::id).toList();
                jdbc.update(MARK_SENDING, Map.of("ids", ids, "until", timestamp(lockedUntil)));
            }
            return List.copyOf(held);
        }));
    }

    /**
     * Records how an attempt on a claimed row ended, in a transaction of its own. Only a row still {@code SENDING}
     * changes, so a row the reaper already handed on is left to its new holder.
     *
     * @param id the row
     * @param outcome what the attempt ended in
     * @param now the current time, recorded as the send time for {@link Outcome#SENT}
     * @param nextAttemptAt when to try again, for {@link Outcome#RETRY}; ignored otherwise
     * @param error what went wrong, at most 500 characters kept; {@code null} for {@link Outcome#SENT}
     * @return {@code true} if the row was still this claim's and changed
     */
    boolean release(long id, Outcome outcome, Instant now, @Nullable Instant nextAttemptAt, @Nullable String error) {
        String kept = error == null ? null : error.substring(0, Math.min(error.length(), 500));
        Integer changed = ownTransaction.execute(status -> switch (outcome) {
            case SENT ->
                entityManager
                        .createQuery(MARK_SENT)
                        .setParameter("next", OutboxStatus.SENT)
                        .setParameter("now", now)
                        .setParameter("id", id)
                        .setParameter("sending", OutboxStatus.SENDING)
                        .executeUpdate();
            case RETRY, FAILED ->
                entityManager
                        .createQuery(MARK_FAILED)
                        .setParameter("next", outcome == Outcome.RETRY ? OutboxStatus.PENDING : OutboxStatus.FAILED)
                        .setParameter("due", nextAttemptAt == null || outcome == Outcome.FAILED ? now : nextAttemptAt)
                        .setParameter("error", kept)
                        .setParameter("id", id)
                        .setParameter("sending", OutboxStatus.SENDING)
                        .executeUpdate();
        });
        return Objects.requireNonNullElse(changed, 0) == 1;
    }

    /**
     * Returns rows whose claim lapsed (a dispatcher died between claim and release) to {@code PENDING}, due at once,
     * in a transaction of its own. A mail that was sent before the crash is then sent again: delivery is at-least-once.
     *
     * @param now the current time
     * @return how many rows were returned
     */
    int reap(Instant now) {
        Integer reaped = ownTransaction.execute(status -> entityManager
                .createQuery(REAP)
                .setParameter("pending", OutboxStatus.PENDING)
                .setParameter("sending", OutboxStatus.SENDING)
                .setParameter("now", now)
                .executeUpdate());
        return Objects.requireNonNullElse(reaped, 0);
    }

    /**
     * How many rows are in a status, for the metrics; uses the {@code (STATUS, NEXT_ATTEMPT_AT)} index.
     *
     * @param status the status
     * @return the count
     */
    long count(OutboxStatus status) {
        return entityManager
                .createQuery(COUNT, Long.class)
                .setParameter("status", status)
                .getSingleResult();
    }

    /**
     * When the oldest mail still waiting to be sent was queued.
     *
     * @return the instant, or {@code null} when nothing is waiting
     */
    @Nullable
    Instant oldestPending() {
        return entityManager
                .createQuery(OLDEST_PENDING, Instant.class)
                .setParameter("status", OutboxStatus.PENDING)
                .getSingleResult();
    }

    private static OffsetDateTime timestamp(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
