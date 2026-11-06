// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Update requests module. Requests for an update on a case, their answers and reminder nudges.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code user}, {@code security} (case actions), {@code casefile} (and
 * listens to its {@code CommentPosted}), {@code notification} (the request's mail and alert, in the same
 * transaction). Other modules use only the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Update requests")
package io.trailkeep.updaterequest;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
