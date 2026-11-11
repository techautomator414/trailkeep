// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;

/** Violation: the import-only APIs (design §8.1) are for the importer module alone. */
class ImportApiOutsideImporter {

    private final AttachmentImportService attachments;
    private final ClosureImportService closures;
    private final CaseImportService cases;

    ImportApiOutsideImporter(
            AttachmentImportService attachments, ClosureImportService closures, CaseImportService cases) {
        this.attachments = attachments;
        this.closures = closures;
        this.cases = cases;
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
}
