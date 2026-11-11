// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import io.trailkeep.notification.TestMailService;
import io.trailkeep.security.TrailkeepOidcUser;
import io.trailkeep.user.UserDirectoryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * "Send me a test" on the System screen (WP-25 part e): {@code POST /admin/system/test-mail}, with CSRF, queues the
 * chosen template with sample values to the signed-in person's own address and comes back to the screen with the
 * outcome. {@link TestMailService} decides who may (System Admins and Compliance Admins, 403 otherwise) and which
 * templates (422 otherwise); there is no address field.
 */
@Controller
class TestMailController {

    private final TestMailService testMail;
    private final UserDirectoryService directory;

    TestMailController(TestMailService testMail, UserDirectoryService directory) {
        this.testMail = testMail;
        this.directory = directory;
    }

    @PostMapping("/admin/system/test-mail")
    String send(
            @AuthenticationPrincipal TrailkeepOidcUser principal,
            @RequestParam(name = "template", defaultValue = "") String template) {
        TestMailService.Outcome outcome =
                testMail.sendTest(directory.permissionsOf(principal.userId()), principal.displayName(), template);
        return "redirect:/admin/system?test=" + outcome.name() + "#tk-test-mail";
    }
}
