// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import java.time.LocalDate;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Builds a {@link CaseFilter} in tests one criterion at a time; every criterion starts empty, as in {@code none()}. */
final class FilterBuilder {

    private Set<CaseStatus> statuses = Set.of();
    private Set<SlaState> slaStates = Set.of();
    private @Nullable CasePriority priority;
    private @Nullable Sensitivity sensitivity;
    private @Nullable LocalDate from;
    private @Nullable LocalDate to;
    private @Nullable Long caseTypeId;
    private @Nullable Long teamId;
    private @Nullable Long ownerId;
    private boolean mine;
    private String text = "";
    private Set<String> numbers = Set.of();

    private FilterBuilder() {}

    static FilterBuilder filter() {
        return new FilterBuilder();
    }

    FilterBuilder statuses(CaseStatus... wanted) {
        statuses = Set.of(wanted);
        return this;
    }

    FilterBuilder sla(SlaState... wanted) {
        slaStates = Set.of(wanted);
        return this;
    }

    FilterBuilder priority(@Nullable CasePriority wanted) {
        priority = wanted;
        return this;
    }

    FilterBuilder sensitivity(@Nullable Sensitivity wanted) {
        sensitivity = wanted;
        return this;
    }

    FilterBuilder identified(@Nullable LocalDate first, @Nullable LocalDate last) {
        from = first;
        to = last;
        return this;
    }

    FilterBuilder type(@Nullable Long wanted) {
        caseTypeId = wanted;
        return this;
    }

    FilterBuilder team(@Nullable Long wanted) {
        teamId = wanted;
        return this;
    }

    FilterBuilder owner(@Nullable Long wanted) {
        ownerId = wanted;
        return this;
    }

    FilterBuilder mine() {
        mine = true;
        return this;
    }

    FilterBuilder text(String wanted) {
        text = wanted;
        return this;
    }

    FilterBuilder numbers(String... selected) {
        numbers = Set.of(selected);
        return this;
    }

    CaseFilter build() {
        return new CaseFilter(
                statuses, slaStates, priority, sensitivity, from, to, caseTypeId, teamId, ownerId, mine, text, numbers);
    }
}
