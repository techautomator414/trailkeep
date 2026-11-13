// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;
import io.trailkeep.sla.GoLiveAlertService;
import io.trailkeep.user.ImportPeopleDirectory;

/** Violation: the import-only APIs (design §8.1) and identity lookups are for the importer module alone. */
class ImportApiOutsideImporter {

    private final AttachmentImportService attachments;
    private final ClosureImportService closures;
    private final CaseImportService cases;
    private final ImportedHistoryService history;
    private final ImportPeopleDirectory people;
    private final GoLiveAlertService alerts;

    ImportApiOutsideImporter(
            AttachmentImportService attachments,
            ClosureImportService closures,
            CaseImportService cases,
            ImportedHistoryService history,
            ImportPeopleDirectory people,
            GoLiveAlertService alerts) {
        this.attachments = attachments;
        this.closures = closures;
        this.cases = cases;
        this.history = history;
        this.people = people;
        this.alerts = alerts;
    }

    AttachmentImportService attachments() {
        return attachments;
    }

    ClosureImportService closures() {
        return closures;
    }

    CaseImportService cases() {
        return cases;
    }

    ImportedHistoryService history() {
        return history;
    }

    ImportPeopleDirectory people() {
        return people;
    }

    GoLiveAlertService alerts() {
        return alerts;
    }
}
