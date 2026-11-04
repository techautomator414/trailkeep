// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The one anchor rule (BUILD_PLAN §6, ADR-0007) for a new case: the clock starts at the end of the identified-on day
 * in {@code app.timezone}, or at the opening instant; {@code due = anchor + target days} and {@code at risk = anchor +
 * target days × at-risk %}. Calendar days; the day count follows the zone, so a daylight-saving change does not
 * shift the due time of day.
 *
 * @param atRiskAt when the case turns at risk
 * @param dueAt when the case is due
 */
record SlaClock(Instant atRiskAt, Instant dueAt) {

    /**
     * The clock of a new case.
     *
     * @param policy the case type's policy
     * @param identifiedOn the day the matter was identified
     * @param openedAt when the case was opened
     * @param zone the organization's time zone
     * @return the at-risk and due instants
     */
    static SlaClock start(SlaPolicyCommand policy, LocalDate identifiedOn, Instant openedAt, ZoneId zone) {
        Instant anchor = switch (policy.anchor()) {
            case IDENTIFIED_ON -> identifiedOn.plusDays(1).atStartOfDay(zone).toInstant();
            case OPENED_AT -> openedAt;
        };
        Instant due = anchor.atZone(zone).plusDays(policy.targetDays()).toInstant();
        Duration untilAtRisk =
                Duration.between(anchor, due).multipliedBy(policy.atRiskPct()).dividedBy(100);
        return new SlaClock(anchor.plus(untilAtRisk), due);
    }
}
