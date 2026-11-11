// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;

/** Violation: the import-only APIs (design §8.1) are for the importer module alone. */
class ImportApiOutsideImporter {

    private final AttachmentImportService attachments;
    private final ClosureImportService closures;
    private final CaseImportService cases;
    private final ImportedHistoryService history;

    ImportApiOutsideImporter(
            AttachmentImportService attachments,
            ClosureImportService closures,
            CaseImportService cases,
            ImportedHistoryService history) {
        this.attachments = attachments;
        this.closures = closures;
        this.cases = cases;
        this.history = history;
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
}
