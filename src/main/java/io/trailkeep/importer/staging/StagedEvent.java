// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer.staging;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.Length;
import org.jspecify.annotations.Nullable;

/** One entry of a case's legacy change history (V13 {@code IMPORT_STG_EVENT}, design §5, §7.5). */
@Entity
@Table(name = "IMPORT_STG_EVENT")
public class StagedEvent extends StagedRow {

    @Column(name = "CASE_KEY", nullable = false, length = 100)
    private String caseKey = "";

    @Column(name = "EVENT_AT")
    private @Nullable Instant eventAt;

    @Column(name = "WHO_KEY", length = 100)
    private @Nullable String whoKey;

    @Column(name = "WHO_NAME", length = 200)
    private @Nullable String whoName;

    @Column(name = "KIND", length = 32)
    private @Nullable String kind;

    @Column(name = "FIELD_NAME", length = 64)
    private @Nullable String fieldName;

    @Column(name = "OLD_VALUE", length = Length.LONG32)
    private @Nullable String oldValue;

    @Column(name = "NEW_VALUE", length = Length.LONG32)
    private @Nullable String newValue;

    /** For JPA. */
    protected StagedEvent() {}
}
