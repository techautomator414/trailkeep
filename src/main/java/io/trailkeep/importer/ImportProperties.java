// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The importer Job's arguments under {@code import} (design §2.1): one command per Job run, for one run id from the
 * authenticated run request, for example {@code --import.command=status --import.run=42}. Read only by the {@code
 * importer} profile.
 *
 * @param command the command, or {@code null} outside the importer Job
 * @param run the run id, or {@code null} outside the importer Job
 */
@ConfigurationProperties("import")
record ImportProperties(@Nullable String command, @Nullable Long run) {}
