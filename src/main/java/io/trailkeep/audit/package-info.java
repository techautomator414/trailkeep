// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Audit module. The append-only audit trail: {@code AuditEvent}, {@code AuditRecorder} and the read-only
 * audit repository. Writes are synchronous in the originating transaction; reads are case-scoped.
 *
 * <p>May call: {@code shared}. Every domain module may call its exported service. Other modules use only the {@code
 * *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Audit")
package io.trailkeep.audit;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
