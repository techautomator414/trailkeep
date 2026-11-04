// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Web module. Controllers, htmx fragments, view models and the single error-handling advice. Templates
 * never receive entities.
 *
 * <p>Shell (WP-05): {@code templates/web/layout.html} is the one layout; {@code ErrorAdvice} maps
 * {@link io.trailkeep.shared.DomainException} to the error page or the htmx {@code problem} fragment;
 * {@code static/js/app.js} implements the client side of the fragment contract.
 *
 * <p>May call: the exported {@code *Service} interfaces and records of any module. No module may call
 * {@code web}. Other modules use only the {@code *Service} interfaces and records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Web")
package io.trailkeep.web;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
