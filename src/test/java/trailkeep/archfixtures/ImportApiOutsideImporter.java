// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package trailkeep.archfixtures;

import io.trailkeep.document.AttachmentImportService;

/** Violation: the import-only APIs (design §8.1) are for the importer module alone. */
class ImportApiOutsideImporter {

    private final AttachmentImportService imports;

    ImportApiOutsideImporter(AttachmentImportService imports) {
        this.imports = imports;
    }

    AttachmentImportService imports() {
        return imports;
    }
}
