// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Notifications module. Email templates, the transactional outbox and its dispatcher, and in-app alerts.
 * It reacts to domain events; it never calls the module that raised them.
 *
 * <p>May call: {@code shared}, {@code audit}, {@code user}. Other modules use only the {@code *Service} interfaces and
 * records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Notifications")
package io.trailkeep.notification;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
