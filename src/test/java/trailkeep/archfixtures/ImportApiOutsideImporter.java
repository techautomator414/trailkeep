// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;

/** Violation: the import-only APIs (design §8.1) are for the importer module alone. */
class ImportApiOutsideImporter {

    private final AttachmentImportService attachments;
    private final ClosureImportService closures;

    ImportApiOutsideImporter(AttachmentImportService attachments, ClosureImportService closures) {
        this.attachments = attachments;
        this.closures = closures;
    }

    AttachmentImportService attachments() {
        return attachments;
    }

    ClosureImportService closures() {
        return closures;
    }
}
