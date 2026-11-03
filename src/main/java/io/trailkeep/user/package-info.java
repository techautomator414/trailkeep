// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Users and teams module. Application users, teams, team membership and global roles, and the services behind
 * the user and team admin screens. Users are provisioned from the identity provider; no passwords are stored.
 *
 * <p>May call: {@code shared}, {@code audit}. Other modules use only the {@code *Service} interfaces and records in
 * this package.
 */
@NullMarked
@ApplicationModule(displayName = "Users and teams")
package io.trailkeep.user;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
