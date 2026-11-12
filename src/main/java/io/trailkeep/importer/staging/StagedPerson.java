// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import org.jspecify.annotations.Nullable;

/** A legacy person, staged only to resolve owners and authors (V13 {@code IMPORT_STG_PERSON}). */
@Entity
@Table(name = "IMPORT_STG_PERSON")
public class StagedPerson extends StagedRow {

    @Column(name = "DISPLAY_NAME", length = 200)
    private @Nullable String displayName;

    @Column(name = "EMAIL", length = 320)
    private @Nullable String email;

    @Column(name = "EXTERNAL_ID", length = 255)
    private @Nullable String externalId;

    /** For JPA. */
    protected StagedPerson() {}

    /**
     * A legacy person as the extract staged it.
     *
     * @param key its run and canonical key
     * @param rowHash the keyed hash of its canonical row
     * @param values its mapped fields, typed
     */
    public StagedPerson(StagingKey key, String rowHash, StagedValues values) {
        super(key, rowHash);
        this.displayName = values.text("displayName");
        this.email = values.text("email");
        this.externalId = values.text("externalId");
    }

    public @Nullable String getEmail() {
        return email;
    }

    public @Nullable String getExternalId() {
        return externalId;
    }
}
