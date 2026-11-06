// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseService.Choice;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.user.TeamAdminService.TeamSummary;
import io.trailkeep.user.UserDirectoryService;
import java.time.InstantSource;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads a page of the cases list in six statements, whatever its size: the count, the page's rows, the team and
 * owner pairs of readable cases, their teams' names, their owners' names and the case types. Every case-rooted read
 * takes the actor's scope.
 */
@Service
@Transactional(readOnly = true)
@CaseAccessPath
class DefaultCaseListService implements CaseListService {

    private final CaseRecordRepository cases;
    private final CaseTypeRepository caseTypes;
    private final CaseScopeService scopes;
    private final UserDirectoryService directory;
    private final InstantSource clock;
    private final ZoneId zone;

    DefaultCaseListService(
            CaseRecordRepository cases,
            CaseTypeRepository caseTypes,
            CaseScopeService scopes,
            UserDirectoryService directory,
            InstantSource clock,
            AppProperties app) {
        this.cases = cases;
        this.caseTypes = caseTypes;
        this.scopes = scopes;
        this.directory = directory;
        this.clock = clock;
        this.zone = app.timezone();
    }

    @Override
    public CaseList list(Actor actor, CaseListQuery requested) {
        CaseScope scope = scopes.forUser(actor.permissions());
        CaseFilter filter = requested.filter().forOwner(actor.permissions().userId());
        long total = cases.count(scope, filter);
        int pages = (int) Math.max(1, Math.min(Math.ceilDiv(total, PAGE_SIZE), Integer.MAX_VALUE));
        CaseListQuery query =
                new CaseListQuery(filter, requested.sort(), requested.descending(), Math.min(requested.page(), pages));
        List<CaseListRow> rows = cases.list(scope, query, PAGE_SIZE);
        List<CaseListRow.TeamOwner> pairs = cases.teamsAndOwners(scope);
        Map<Long, String> teams = directory.teams(ids(pairs, CaseListRow.TeamOwner::teamId)).values().stream()
                .collect(Collectors.toUnmodifiableMap(TeamSummary::id, TeamSummary::name));
        Map<Long, String> owners = directory.displayNames(ids(pairs, CaseListRow.TeamOwner::ownerId));
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
        List<Choice> types = caseTypes.findAllByOrderByNameAsc().stream()
                .map(type -> new Choice(Objects.requireNonNull(type.getId()), type.getName()))
                .toList();
        return new CaseList(
                shown,
                total,
                query.page(),
                pages,
                types,
                byName(teams),
                byName(owners),
                scopes.readsOthersRestricted(actor.permissions()));
    }

    private static Set<Long> ids(List<CaseListRow.TeamOwner> pairs, ToLongFunction<CaseListRow.TeamOwner> id) {
        return pairs.stream().map(pair -> id.applyAsLong(pair)).collect(Collectors.toUnmodifiableSet());
    }

    private static List<Choice> byName(Map<Long, String> names) {
        return names.entrySet().stream()
                .map(entry -> new Choice(entry.getKey(), entry.getValue()))
                .sorted(Comparator.comparing(Choice::name).thenComparingLong(Choice::id))
                .toList();
    }
}
