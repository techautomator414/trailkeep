// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import java.time.Instant;

/**
 * Go-live alert pre-marking (WP-26 part ac, docs/design/importer.md §7.1, §8.1 relaxation 10, ADR-0016): an imported
 * open case arrives with its SLA state as of the load, and the alerts that state would already have raised are
 * recorded in {@code ALERT_LEDGER} as sent, so the evaluator and the reminders tick send no wave of mail for years-old
 * cases; later transitions and later repeat periods alert as for any case. Called only by the importer (ArchUnit
 * {@code ImportOnlyApiRulesTest}); nothing is sent.
 */
public interface GoLiveAlertService {

    /**
     * Records, in the caller's transaction, the alerts an imported case's SLA has already raised in its current cycle:
     * {@code SLA_AT_RISK} for an at-risk case; {@code SLA_OVERDUE} for an overdue one and, once its first repeat is
     * due, {@code SLA_OVERDUE_REPEAT} for the period {@code at} falls in. Nothing for any other state.
     *
     * @param caseId the imported case, its SLA row already written
     * @param at the load's instant, the one its SLA state was computed at
     * @return how many alerts were recorded
     */
    int preMark(long caseId, Instant at);
}
