// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Documents module. Case attachments: upload, content-type detection, antivirus scanning, quarantine and
 * release, chunked storage and streaming download.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code casefile}. Other modules use only the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Documents")
package io.trailkeep.document;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
