// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a snapshot-record component whose value must never be stored in the audit trail as text, such as a case's
 * subject or description (BUILD_PLAN section 9, Part A #16). {@link AuditRecorder#diff} stores it as
 * {@code {"hmac": HMAC-SHA-256(value, app.audit.hmac-key) in hex, "length": character count}}: enough to tell that
 * and when the value changed, and to confirm a known value, but not reversible by dictionary without the server key.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.RECORD_COMPONENT)
public @interface Redacted {}
