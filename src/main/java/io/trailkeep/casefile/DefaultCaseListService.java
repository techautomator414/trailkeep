// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a page of the cases list in four statements, whatever its size: the count, the page's rows, the names of its
 * teams and the names of its owners. Every case-rooted read takes the actor's scope.
 */
@Service
@Transactional(readOnly = true)
@CaseAccessPath
class DefaultCaseListService implements CaseListService {

    private final CaseRecordRepository cases;
    private final CaseScopeService scopes;
    private final UserDirectoryService directory;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultCaseListService(
            CaseRecordRepository cases,
            CaseScopeService scopes,
            UserDirectoryService directory,
            InstantSource clock,
            AppProperties app) {
        this.cases = cases;
        this.scopes = scopes;
        this.directory = directory;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    public CaseList list(Actor actor, CaseListQuery requested) {
        CaseScope scope = scopes.forUser(actor.permissions());
        long total = cases.count(scope);
        int pages = (int) Math.max(1, Math.min(Math.ceilDiv(total, PAGE_SIZE), Integer.MAX_VALUE));
        CaseListQuery query = requested.onPage(Math.min(requested.page(), pages));
        List<CaseListRow> rows = cases.list(scope, query, PAGE_SIZE);
        Map<Long, String> teams = directory.teams(ids(rows, CaseListRow::teamId)).values().stream()
                .collect(Collectors.toUnmodifiableMap(TeamSummary::id, TeamSummary::name));
        Map<Long, String> owners = directory.displayNames(ids(rows, CaseListRow::ownerId));
        LocalDate today = LocalDate.ofInstant(clock.instant(), zone);
        List<CaseList.Row> shown = rows.stream()
                .map(row -> new CaseList.Row(
                        CaseNumber.parse(row.caseNumber()),
                        row.title(),
                        row.status(),
                        row.sensitivity(),
                        row.priority(),
                        row.caseTypeName(),
                        teams.getOrDefault(row.teamId(), ""),
                        owners.getOrDefault(row.ownerId(), ""),
                        LocalDate.ofInstant(row.openedAt(), zone),
                        CaseView.Sla.of(row.slaState(), row.slaDueAt(), today, zone)))
                .toList();
        return new CaseList(shown, total, query.page(), pages);
    }

    private static Set<Long> ids(List<CaseListRow> rows, ToLongFunction<CaseListRow> id) {
        return rows.stream().map(row -> id.applyAsLong(row)).collect(Collectors.toUnmodifiableSet());
    }
}
