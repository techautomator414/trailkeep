// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Audit module. The append-only audit trail. Exported API: {@link io.trailkeep.audit.AuditRecorder} (write, in the
 * caller's transaction, plus the redacting snapshot diff driven by {@link io.trailkeep.audit.Redacted}) and
 * {@link io.trailkeep.audit.AuditTrailService} (case-scoped reads), with their records. The entity, the
 * {@code @CaseRooted} repository and the HMAC key handling are package-private.
 *
 * <p>May call: {@code shared}. Every domain module may call its exported service. Other modules use only the {@code
 * *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Audit")
package io.trailkeep.audit;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
