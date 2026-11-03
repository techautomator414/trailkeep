// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures.web;

/** Compliant: the web layer works with records. */
class GoodController {

    record CaseView(String caseNumber, String title) {}

    String render(CaseView view) {
        return view.caseNumber();
    }
}
