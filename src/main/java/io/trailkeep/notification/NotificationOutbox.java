// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/**
 * One email in the outbox ({@code NOTIFICATION_OUTBOX}, BUILD_PLAN §5, §6). The entity only inserts: the claim, the
 * status changes and the reaper are statements in {@link OutboxJdbcDao}, so the row is written by the dispatcher
 * without loading it, and it has no {@code @Version} (BUILD_PLAN §8 rule 6: job-written state). The bodies are
 * rendered at enqueue time; they hold a deep link, never a case's description or subject text (§9).
 */
@Entity
@Table(name = "NOTIFICATION_OUTBOX")
class NotificationOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "notificationOutboxSeq")
    @SequenceGenerator(name = "notificationOutboxSeq", sequenceName = "SEQ_NOTIFICATION_OUTBOX", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "RECIPIENT", nullable = false, updatable = false, length = 320)
    private String recipient = "";

    @Column(name = "TEMPLATE_ID", nullable = false, updatable = false, length = 60)
    private String templateId = "";

    @Column(name = "SUBJECT", nullable = false, updatable = false, length = 200)
    private String subject = "";

    @Column(name = "BODY_TEXT", nullable = false, updatable = false, length = Length.LONG32)
    private String bodyText = "";

    @Column(name = "BODY_HTML", nullable = false, updatable = false, length = Length.LONG32)
    private String bodyHtml = "";

    @Column(name = "CASE_ID", updatable = false)
    private @Nullable Long caseId;

    @Column(name = "REPORT_RUN_ID", updatable = false)
    private @Nullable Long reportRunId;

    @Enumerated(EnumType.STRING)
    @Column(name = "STATUS", nullable = false, length = 10)
    private OutboxStatus status = OutboxStatus.PENDING;

    @Column(name = "ATTEMPTS", nullable = false)
    private int attempts;

    @Column(name = "NEXT_ATTEMPT_AT", nullable = false)
    private Instant nextAttemptAt = Instant.EPOCH;

    @Column(name = "LOCKED_UNTIL")
    private @Nullable Instant lockedUntil;

    @Column(name = "SENT_AT")
    private @Nullable Instant sentAt;

    @Column(name = "LAST_ERROR", length = 500)
    private @Nullable String lastError;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.EPOCH;

    /** For JPA. */
    protected NotificationOutbox() {}

    /**
     * A new pending email, due at once.
     *
     * @param recipient the address
     * @param templateId the template it was rendered from
     * @param mail the rendered subject and bodies
     * @param caseId the case it is about, or {@code null}
     * @param reportRunId the scheduled report it delivers, or {@code null}
     * @param now when it was queued, and when it is first due
     */
    NotificationOutbox(
            String recipient,
            String templateId,
            RenderedMail mail,
            @Nullable Long caseId,
            @Nullable Long reportRunId,
            Instant now) {
        this.recipient = recipient;
        this.templateId = templateId;
        this.subject = mail.subject();
        this.bodyText = mail.text();
        this.bodyHtml = mail.html();
        this.caseId = caseId;
        this.reportRunId = reportRunId;
        this.nextAttemptAt = now;
        this.createdAt = now;
    }

    @Nullable
    Long getId() {
        return id;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof NotificationOutbox that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return NotificationOutbox.class.hashCode();
    }

    /** Never the recipient's address or the bodies (Part B "Logging"). */
    @Override
    public String toString() {
        return "NotificationOutbox[id=" + id + ", template=" + templateId + ", status=" + status + "]";
    }
}
