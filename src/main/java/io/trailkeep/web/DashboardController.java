// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseFilter;
import io.trailkeep.casefile.CaseList;
import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseListService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.CaseSort;
import io.trailkeep.casefile.SlaState;
import io.trailkeep.closure.ClosureService;
import io.trailkeep.closure.ClosureService.AwaitingApproval;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Paged;
import io.trailkeep.updaterequest.UpdateRequestService;
import io.trailkeep.updaterequest.UpdateRequestService.OpenRequest;
import java.util.List;
import java.util.Set;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * The landing page after login (BUILD_PLAN §4.1, WP-20), everything through the person's {@code CaseScope}:
 * <ul>
 *   <li>KPI tiles, each a count in text and a link: my open cases, at risk and overdue (over the cases the person
 *       reads), each to the cases list so filtered; pending my approval and update requests for me, to their lists
 *       below;
 *   <li>"My cases": the person's open cases by SLA due date, at most ten, with a link to all of them;
 *   <li>"Needs attention": the closure requests waiting for the person's decision, oldest first, each with Approve,
 *       and the update requests addressed to them, newest first; each list paged by 20 with plain links
 *       ({@code approvals} and {@code requests} name the pages).
 * </ul>
 */
@Controller
class DashboardController {

    private static final int MY_CASES = 10;

    private final CaseListService lists;
    private final UpdateRequestService requests;
    private final ClosureService closures;
    private final CasePage page;

    DashboardController(CaseListService lists, UpdateRequestService requests, ClosureService closures, CasePage page) {
        this.lists = lists;
        this.requests = requests;
        this.closures = closures;
        this.page = page;
    }

    /**
     * A KPI tile.
     *
     * @param key its label's message key, after {@code tk.dashboard.tile.}
     * @param count the count
     * @param href where it leads
     */
    record Tile(String key, long count, String href) {}

    @GetMapping("/")
    String dashboard(
            @AuthenticationPrincipal TrailkeepOidcUser user,
            @RequestParam(defaultValue = "1") String approvals,
            @RequestParam(defaultValue = "1") String requests,
            Model model) {
        Actor actor = page.actor(user);
        Paged<AwaitingApproval> waiting = closures.awaitingMyApproval(actor, number(approvals));
        Paged<OpenRequest> asked = this.requests.openFor(actor, number(requests));
        CaseListQuery mine = query(Set.of(CaseStatus.OPEN, CaseStatus.PENDING_CLOSURE), Set.of(), true);
        CaseListQuery atRisk = query(Set.of(), Set.of(SlaState.AT_RISK), false);
        CaseListQuery overdue = query(Set.of(), Set.of(SlaState.OVERDUE), false);
        CaseList myCases = lists.list(actor, mine);
        model.addAttribute("displayName", user.displayName());
        model.addAttribute(
                "tiles",
                List.of(
                        new Tile("mine", myCases.total(), cases(mine)),
                        new Tile("atRisk", lists.count(actor, atRisk.filter()), cases(atRisk)),
                        new Tile("overdue", lists.count(actor, overdue.filter()), cases(overdue)),
                        new Tile("approvals", waiting.total(), "#tk-approvals"),
                        new Tile("requests", asked.total(), "#tk-requests")));
        model.addAttribute("myCases", myCases.rows().stream().limit(MY_CASES).toList());
        model.addAttribute("myCasesTotal", myCases.total());
        model.addAttribute("allMine", cases(mine));
        model.addAttribute("approvals", waiting);
        model.addAttribute("attention", asked);
        return "web/dashboard";
    }

    /** A cases-list query by SLA due date, soonest first. */
    private static CaseListQuery query(Set<CaseStatus> statuses, Set<SlaState> sla, boolean mine) {
        return new CaseListQuery(
                new CaseFilter(statuses, sla, null, null, null, null, null, null, null, mine, "", Set.of()),
                CaseSort.DUE,
                false,
                1);
    }

    private static String cases(CaseListQuery query) {
        return "/cases?" + query.toQueryString();
    }

    /** A page number as sent; anything unreadable is the first page. */
    private static int number(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException malformed) {
            return 1;
        }
    }
}
