// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.sla;

import io.trailkeep.casefile.CaseSlaService;
import io.trailkeep.casefile.CaseSlaService.OverdueCase;
import io.trailkeep.casefile.CaseSlaService.Standing;
import io.trailkeep.casefile.SlaState;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link GoLiveAlertService}: the ledger rows the evaluator and the reminders tick would have written, with the same
 * keys, so neither ever writes them again. The evaluator raises once-per-cycle alerts only on a transition into the
 * state, which an imported case already holds, so a pre-marked {@code SLA_AT_RISK} or {@code SLA_OVERDUE} never
 * collides with one it writes later; the overdue repeat follows {@link OverdueCase#repeatPeriod}, the tick's rule.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
class DefaultGoLiveAlertService implements GoLiveAlertService {

    private final CaseSlaService slas;
    private final EntityManager entityManager;

    DefaultGoLiveAlertService(CaseSlaService slas, EntityManager entityManager) {
        this.slas = slas;
        this.entityManager = entityManager;
    }

    @Override
    public int preMark(long caseId, Instant at) {
        Standing sla = slas.standing(caseId).orElse(null);
        List<AlertLedger> alerts = new ArrayList<>();
        if (sla != null && sla.state() == SlaState.AT_RISK) {
            alerts.add(new AlertLedger(caseId, AlertType.SLA_AT_RISK, sla.cycle(), at));
        }
        if (sla != null && sla.state() == SlaState.OVERDUE) {
            alerts.add(new AlertLedger(caseId, AlertType.SLA_OVERDUE, sla.cycle(), at));
            OverdueCase due = sla.due();
            String period = due == null ? null : due.repeatPeriod(at);
            if (period != null) {
                alerts.add(new AlertLedger(caseId, AlertType.SLA_OVERDUE_REPEAT, sla.cycle(), period, at));
            }
        }
        alerts.forEach(entityManager::persist);
        return alerts.size();
    }
}
