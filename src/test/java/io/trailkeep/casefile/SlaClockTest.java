// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class SlaClockTest {

    private static final Instant OPENED = Instant.parse("2026-11-04T15:30:00Z");

    @Test
    void identifiedOnAnchorStartsAtTheEndOfThatDayInTheOrganizationsZone() {
        SlaClock clock = SlaClock.start(
                policy(SlaAnchor.IDENTIFIED_ON, 30, 80), LocalDate.of(2026, 11, 1), OPENED, ZoneOffset.UTC);

        assertThat(clock.dueAt()).isEqualTo(Instant.parse("2026-12-02T00:00:00Z"));
        assertThat(clock.atRiskAt()).isEqualTo(Instant.parse("2026-11-26T00:00:00Z"));
    }

    @Test
    void openedAtAnchorStartsAtTheOpeningInstant() {
        SlaClock clock =
                SlaClock.start(policy(SlaAnchor.OPENED_AT, 5, 60), LocalDate.of(2026, 10, 1), OPENED, ZoneOffset.UTC);

        assertThat(clock.dueAt()).isEqualTo(Instant.parse("2026-11-09T15:30:00Z"));
        assertThat(clock.atRiskAt()).isEqualTo(Instant.parse("2026-11-07T15:30:00Z"));
    }

    @Test
    void calendarDaysFollowTheZoneAcrossDaylightSavingChanges() {
        ZoneId newYork = ZoneId.of("America/New_York"); // clocks go back on 2026-11-01
        SlaClock clock =
                SlaClock.start(policy(SlaAnchor.IDENTIFIED_ON, 10, 50), LocalDate.of(2026, 10, 25), OPENED, newYork);

        // Anchor 2026-10-26T00:00-04:00; due ten calendar days later at local midnight, by then at -05:00 (241 h).
        assertThat(clock.dueAt()).isEqualTo(Instant.parse("2026-11-05T05:00:00Z"));
        assertThat(clock.atRiskAt()).isEqualTo(Instant.parse("2026-10-31T04:30:00Z"));
    }

    /** The dry run's forecast (WP-26 part ac): an overdue case's first repeat is due a reminder period after due. */
    @Test
    void forecastSaysWhenAnOverdueCasesFirstRepeatIsDueByTheRemindersTicksRule() {
        SlaClock clock = SlaClock.start(
                policy(SlaAnchor.IDENTIFIED_ON, 30, 80), LocalDate.of(2026, 11, 1), OPENED, ZoneOffset.UTC);
        Instant due = clock.dueAt();

        assertThat(CaseSla.importedOpen(1, clock, due.minusSeconds(1)).forecast(7, due.minusSeconds(1)))
                .isEqualTo(new CaseImportService.SlaForecast.Forecast(SlaState.AT_RISK, false));
        Instant sixDays = due.plus(Duration.ofDays(7)).minusSeconds(1);
        assertThat(CaseSla.importedOpen(1, clock, sixDays).forecast(7, sixDays))
                .isEqualTo(new CaseImportService.SlaForecast.Forecast(SlaState.OVERDUE, false));
        Instant week = due.plus(Duration.ofDays(7));
        assertThat(CaseSla.importedOpen(1, clock, week).forecast(7, week))
                .isEqualTo(new CaseImportService.SlaForecast.Forecast(SlaState.OVERDUE, true));
    }

    private static SlaPolicyCommand policy(SlaAnchor anchor, int targetDays, int atRiskPct) {
        return new SlaPolicyCommand("Synthetic", anchor, targetDays, atRiskPct, 7, EscalateTo.OWNER);
    }
}
