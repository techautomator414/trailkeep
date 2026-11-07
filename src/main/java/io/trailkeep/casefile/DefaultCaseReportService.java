// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.config.AppProperties;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The reports' counts through the actor's {@code CaseScope}; see {@link CaseReportService}. */
@Service
@Transactional(readOnly = true)
@CaseAccessPath
class DefaultCaseReportService implements CaseReportService {

    private final CaseRecordRepository cases;
    private final CaseScopeService scopes;
    private final ZoneId zone;

    DefaultCaseReportService(CaseRecordRepository cases, CaseScopeService scopes, AppProperties app) {
        this.cases = cases;
        this.scopes = scopes;
        this.zone = app.timezone();
    }

    @Override
    public List<AgingRow> aging(Actor actor, LocalDate today) {
        return cases.aging(scopes.forUser(actor.permissions()), today);
    }

    @Override
    public List<ComplianceRow> compliance(Actor actor, LocalDate from, LocalDate to) {
        return cases.compliance(
                scopes.forUser(actor.permissions()),
                from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant());
    }
}
