// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import org.hibernate.annotations.Immutable;
import org.jspecify.annotations.Nullable;

/**
 * A person's saved view of the cases list ({@code SAVED_VIEW}, BUILD_PLAN §4.4). It belongs to one person and holds
 * the canonical query string of a {@link CaseListQuery} on page 1, which is read back through {@link
 * CaseListQuery#parse} and the person's scope like any URL. A view is saved and deleted, never changed ({@code
 * Immutable}). Not case-rooted: it holds no case data, only criteria.
 */
@Entity
@Immutable
@Table(name = "SAVED_VIEW")
class SavedView {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "savedViewSeq")
    @SequenceGenerator(name = "savedViewSeq", sequenceName = "SEQ_SAVED_VIEW", allocationSize = 50)
    @Column(name = "ID", nullable = false, updatable = false)
    private @Nullable Long id;

    @Column(name = "USER_ID", nullable = false, updatable = false)
    private long userId;

    @Column(name = "NAME", nullable = false, updatable = false, length = SavedViewService.MAX_NAME)
    private String name = "";

    @Column(name = "QUERY", nullable = false, updatable = false, length = SavedViewService.MAX_QUERY)
    private String query = "";

    @Column(name = "CREATED_AT", nullable = false, updatable = false)
    private Instant createdAt = Instant.EPOCH;

    /** For JPA. */
    protected SavedView() {}

    /**
     * A new view.
     *
     * @param userId whose view it is
     * @param name its name, stripped
     * @param query the canonical query string
     * @param createdAt when it was saved
     */
    SavedView(long userId, String name, String query, Instant createdAt) {
        this.userId = userId;
        this.name = name;
        this.query = query;
        this.createdAt = createdAt;
    }

    @Nullable
    Long getId() {
        return id;
    }

    long getUserId() {
        return userId;
    }

    String getName() {
        return name;
    }

    String getQuery() {
        return query;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof SavedView that && id != null && Objects.equals(id, that.id));
    }

    @Override
    public int hashCode() {
        return SavedView.class.hashCode();
    }

    @Override
    public String toString() {
        return "SavedView[id=" + id + ", userId=" + userId + "]";
    }
}
