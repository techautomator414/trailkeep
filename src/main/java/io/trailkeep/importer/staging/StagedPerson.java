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
}
