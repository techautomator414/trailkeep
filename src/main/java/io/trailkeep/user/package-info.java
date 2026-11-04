// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Users and teams module. Application users, teams, team membership and global roles, and the services behind
 * the user and team admin screens. Users are provisioned from the identity provider; no passwords are stored.
 *
 * <p>Persistence ({@code V1__identity}): the entities {@code AppUser}, {@code Team}, {@code AppUserTeam} and
 * {@code AppUserGlobalRole} and their repositories are package-private; none of these tables is case-rooted. The
 * public types are the role enums {@link io.trailkeep.user.TeamRole} and {@link io.trailkeep.user.GlobalRole}, and
 * the sign-in API {@link io.trailkeep.user.UserSignInService} with its records.
 *
 * <p>May call: {@code shared}, {@code audit}. Other modules use only the {@code *Service} interfaces and records in
 * this package.
 */
@NullMarked
@ApplicationModule(displayName = "Users and teams")
package io.trailkeep.user;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
