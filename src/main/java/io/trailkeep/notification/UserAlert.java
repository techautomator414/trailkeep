// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.notification;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * One in-app alert ({@code USER_ALERT}, BUILD_PLAN §4.7, §5): what a person was told, with a link into the
 * application, written in the transaction of the event alongside its mail. The person marks it read or deletes it,
 * always looked up with their own id. It has no {@code @Version}: marking read twice, or concurrently, ends in the
 * same state, and nothing else about it changes. The message never holds a case's description or subject text (§9).
 */
@Entity
@Table(name = "USER_ALERT")
class UserAlert {

    /** The longest message, in characters. */
    static final int MAX_MESSAGE = 300;

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "userAlertSeq")
    @SequenceGenerator(name = "userAlertSeq", sequenceName = "SEQ_USER_ALERT", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "USER_ID", nullable = false, updatable = false)
    private long userId;

    @Column(name = "MESSAGE", nullable = false, updatable = false, length = MAX_MESSAGE)
    private String message = "";

    @Column(name = "LINK", nullable = false, updatable = false, length = 500)
    private String link = "";

    @Column(name = "CASE_ID", updatable = false)
    private @Nullable Long caseId;

    @Column(name = "READ_AT")
    private @Nullable Instant readAt;

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.EPOCH;

    /** For JPA. */
    protected UserAlert() {}

    /**
     * A new unread alert.
     *
     * @param userId the person
     * @param message what happened, one line
     * @param link the path of the page it is about
     * @param caseId the case it is about, or {@code null}
     * @param now when it happened
     */
    UserAlert(long userId, String message, String link, @Nullable Long caseId, Instant now) {
        this.userId = userId;
        this.message = message;
        this.link = link;
        this.caseId = caseId;
        this.createdAt = now;
    }

    @Nullable
    Long getId() {
        return id;
    }

    String getMessage() {
        return message;
    }

    String getLink() {
        return link;
    }

    @Nullable
    Instant getReadAt() {
        return readAt;
    }

    /**
     * Marks the alert read, once: an alert already read keeps its first time.
     *
     * @param now when the person marked it
     */
    void markRead(Instant now) {
        if (readAt == null) {
            readAt = now;
        }
    }

    Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof UserAlert that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return UserAlert.class.hashCode();
    }

    /** Never the message (Part B "Logging"). */
    @Override
    public String toString() {
        return "UserAlert[id=" + id + ", read=" + (readAt != null) + "]";
    }
}
