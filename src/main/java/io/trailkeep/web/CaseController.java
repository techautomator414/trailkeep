// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.casefile.CaseService;
import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.user.UserDirectoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;

/**
 * The case page (BUILD_PLAN §4.3). The case number is the only identifier in the URL; a case the person may not
 * read answers 404 like an absent one, decided by {@link CaseService} through the person's scope.
 */
@Controller
class CaseController {

    private final CaseService cases;
    private final UserDirectoryService directory;

    CaseController(CaseService cases, UserDirectoryService directory) {
        this.cases = cases;
        this.directory = directory;
    }

    @GetMapping("/cases/{number}")
    String casePage(@AuthenticationPrincipal TrailkeepOidcUser principal, @PathVariable String number, Model model) {
        Actor actor = new Actor(directory.permissionsOf(principal.userId()), principal.displayName());
        model.addAttribute("c", cases.view(actor, number));
        return "casefile/case";
    }
}
