// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import io.trailkeep.audit.AuditEntry;
import io.trailkeep.audit.AuditOrigin;
import io.trailkeep.audit.AuditRecorder;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Instant;
import java.time.InstantSource;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lists and re-queues failed mail (#318). The rows are read as tuples, never as entities and never with their bodies;
 * the change is {@link OutboxJdbcDao#requeue}'s conditional update, counted, and an audit event is written only for a
 * row this call really moved. The recipient and any address inside the relay's reply are masked before they leave this
 * class, so neither the screen nor the audit trail holds a whole address (DATA_MAP "People").
 */
@Service
@Transactional
class DefaultOutboxResendService implements OutboxResendService {

    private static final String ENTITY = "NOTIFICATION_OUTBOX";
    private static final String ACTION = "MAIL_REQUEUED";
    private static final Pattern ADDRESS = Pattern.compile("[^\\s<>\"',;:()@]+@[^\\s<>\"',;()]+");

    private final EntityManager entityManager;
    private final OutboxJdbcDao outbox;
    private final UserAlertQueries scoped;
    private final AuditRecorder audit;
    private final InstantSource clock;

    DefaultOutboxResendService(
            EntityManager entityManager,
            OutboxJdbcDao outbox,
            UserAlertQueries scoped,
            AuditRecorder audit,
            InstantSource clock) {
        this.entityManager = entityManager;
        this.outbox = outbox;
        this.scoped = scoped;
        this.audit = audit;
        this.clock = clock;
    }

    /** What the audit trail keeps of a mail's state: the change shows as status, attempts and the failure reason. */
    record Snapshot(String status, int attempts, @Nullable String lastError) {}

    @Override
    @Transactional(readOnly = true)
    public Failed failed(UserPermissions actor, CaseScope scope) {
        authorize(actor);
        List<Tuple> rows = select(scope, null);
        long total = rows.size() < MAX_BATCH ? rows.size() : count(scope);
        Map<Long, String> numbers = caseNumbers(rows);
        return new Failed(
                total,
                rows.stream()
                        .map(row -> new FailedMail(
                                row.get("id", Long.class),
                                maskAddress(row.get("recipient", String.class)),
                                row.get("templateId", String.class),
                                numbers.get(row.get("caseId", Long.class)),
                                row.get("attempts", Integer.class),
                                maskAddresses(row.get("lastError", String.class)),
                                row.get("failedAt", Instant.class)))
                        .toList());
    }

    @Override
    public Result requeue(UserPermissions actor, String actorName, CaseScope scope, Collection<Long> ids) {
        authorize(actor);
        Set<Long> wanted = Set.copyOf(ids);
        if (wanted.size() > MAX_BATCH) {
            throw new Refused(DomainException.Kind.INVALID, "at most " + MAX_BATCH + " mails at a time");
        }
        if (wanted.isEmpty()) {
            return new Result(0, 0);
        }
        Instant now = clock.instant();
        int requeued = 0;
        for (Tuple row : select(scope, wanted)) {
            long id = row.get("id", Long.class);
            if (outbox.requeue(id, now)) {
                requeued++;
                audit.record(new AuditEntry(
                        actor.userId(),
                        actorName,
                        AuditOrigin.UI,
                        null,
                        ENTITY,
                        id,
                        ACTION,
                        audit.diff(
                                new Snapshot(
                                        OutboxStatus.FAILED.name(),
                                        row.get("attempts", Integer.class),
                                        maskAddresses(row.get("lastError", String.class))),
                                new Snapshot(OutboxStatus.PENDING.name(), 0, null))));
            }
        }
        return new Result(requeued, wanted.size() - requeued);
    }

    private static void authorize(UserPermissions actor) {
        if (!actor.has(GlobalRole.COMPLIANCE_ADMIN) || actor.has(GlobalRole.SYSTEM_ADMIN)) {
            throw new Refused(DomainException.Kind.FORBIDDEN, "re-sending failed mail is for Compliance Admins");
        }
    }

    /** The scope's failed rows, oldest first, at most {@link #MAX_BATCH}; only those of {@code ids} when given. */
    private List<Tuple> select(CaseScope scope, @Nullable Set<Long> ids) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<NotificationOutbox> mail = query.from(NotificationOutbox.class);
        query.select(cb.tuple(
                        mail.get("id").alias("id"),
                        mail.get("recipient").alias("recipient"),
                        mail.get("templateId").alias("templateId"),
                        mail.get("caseId").alias("caseId"),
                        mail.get("attempts").alias("attempts"),
                        mail.get("lastError").alias("lastError"),
                        // No failed-at column exists; MARK_FAILED sets NEXT_ATTEMPT_AT to the final failure time, so it
                        // is shown as such.
                        mail.get("nextAttemptAt").alias("failedAt")))
                .where(where(scope, ids, mail, query, cb))
                .orderBy(cb.asc(mail.get("id")));
        return entityManager.createQuery(query).setMaxResults(MAX_BATCH).getResultList();
    }

    private long count(CaseScope scope) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<NotificationOutbox> mail = query.from(NotificationOutbox.class);
        query.select(cb.count(mail)).where(where(scope, null, mail, query, cb));
        return entityManager.createQuery(query).getSingleResult();
    }

    private Predicate[] where(
            CaseScope scope,
            @Nullable Set<Long> ids,
            Root<NotificationOutbox> mail,
            CriteriaQuery<?> query,
            CriteriaBuilder cb) {
        List<Predicate> all = new ArrayList<>(3);
        all.add(cb.equal(mail.get("status"), OutboxStatus.FAILED));
        if (ids != null) {
            all.add(mail.get("id").in(ids));
        }
        all.add(scoped.listed(scope, mail.get("caseId"), query, cb));
        return all.toArray(Predicate[]::new);
    }

    /** Case numbers for the rows' case ids; {@code notification} reaches the case only by its entity's name (#86). */
    private Map<Long, String> caseNumbers(List<Tuple> rows) {
        Set<Long> caseIds = new HashSet<>();
        rows.forEach(row -> {
            Long caseId = row.get("caseId", Long.class);
            if (caseId != null) {
                caseIds.add(caseId);
            }
        });
        Map<Long, String> numbers = new HashMap<>();
        if (!caseIds.isEmpty()) {
            entityManager
                    .createQuery(
                            "SELECT c.id, c.caseNumber FROM " + UserAlertQueries.CASE_ENTITY + " c WHERE c.id IN :ids",
                            Tuple.class)
                    .setParameter("ids", caseIds)
                    .getResultList()
                    .forEach(row -> numbers.put(row.get(0, Long.class), row.get(1, String.class)));
        }
        return numbers;
    }

    /** {@code a***@example.org}: the first character and the domain, enough to tell a typo from a bounce. */
    static String maskAddress(String address) {
        int at = address.lastIndexOf('@');
        return at < 1 ? "***" : address.charAt(0) + "***" + address.substring(at);
    }

    static @Nullable String maskAddresses(@Nullable String text) {
        return text == null
                ? null
                : ADDRESS.matcher(text).replaceAll(match -> Matcher.quoteReplacement(maskAddress(match.group())));
    }

    /** A refused request: not a Compliance Admin, or too many rows. */
    static final class Refused extends DomainException {

        private static final long serialVersionUID = 1L;

        Refused(Kind kind, String message) {
            super(kind, message);
        }
    }
}
