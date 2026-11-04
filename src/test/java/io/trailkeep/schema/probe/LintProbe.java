// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.schema.probe;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/**
 * Test-only entity for {@code LINT_PROBE} ({@code db/migration/common/V0_1__lint_probe.sql} under {@code src/test}).
 * It exists so the schema-validation harness is never vacuous before the first real entities arrive (PR 6), and so
 * a test can plant drift against a known column. It is never packaged.
 */
@Entity
@Table(name = "LINT_PROBE")
public class LintProbe {

    @Id
    @Column(name = "ID", nullable = false)
    private Long id = 0L;

    @Column(name = "NAME", nullable = false, length = 50)
    private String name = "";

    @Column(name = "ACTIVE", nullable = false)
    private boolean active;

    @Column(name = "NOTE", length = 200)
    private @Nullable String note;

    /** For JPA. */
    protected LintProbe() {}

    /**
     * The identifier.
     *
     * @return the id
     */
    public Long getId() {
        return id;
    }

    /**
     * The name.
     *
     * @return the name
     */
    public String getName() {
        return name;
    }

    /**
     * Whether the probe is active.
     *
     * @return the flag
     */
    public boolean isActive() {
        return active;
    }

    /**
     * The optional note.
     *
     * @return the note, or {@code null}
     */
    public @Nullable String getNote() {
        return note;
    }
}
