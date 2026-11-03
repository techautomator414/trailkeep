// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Web module. Controllers, htmx fragments, view models and the single error-handling advice. Templates
 * never receive entities.
 *
 * <p>May call: the exported {@code *Service} interfaces and records of any module. No module may call
 * {@code web}. Other modules use only the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Web")
package io.trailkeep.web;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
