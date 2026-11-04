// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/** A team ({@code TEAM}) that owns cases. Teams are deactivated, never deleted. */
@Entity
@Table(name = "TEAM")
class Team {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "teamSeq")
    @SequenceGenerator(name = "teamSeq", sequenceName = "SEQ_TEAM", allocationSize = 50)
    @Column(name = "ID", nullable = false)
    private @Nullable Long id;

    @Column(name = "NAME", nullable = false, length = 120)
    private String name;

    @Column(name = "ACTIVE", nullable = false)
    private boolean active = true;

    /** For JPA. */
    protected Team() {
        this.name = "";
    }

    Team(String name) {
        this.name = name;
    }

    @Nullable
    Long getId() {
        return id;
    }

    String getName() {
        return name;
    }

    boolean isActive() {
        return active;
    }

    void rename(String newName) {
        this.name = newName;
    }

    void deactivate() {
        this.active = false;
    }

    void activate() {
        this.active = true;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    @Override
    public boolean equals(@Nullable Object other) {
        return this == other || (other instanceof Team team && id != null && id.equals(team.id));
    }

    @Override
    public int hashCode() {
        return Team.class.hashCode();
    }

    @Override
    public String toString() {
        return "Team[id=" + id + ", name=" + name + ", active=" + active + "]";
    }
}
