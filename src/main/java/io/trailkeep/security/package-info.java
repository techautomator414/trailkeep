// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
/**
 * Security module. OIDC login, session lifetime, role mapping from identity-provider groups, `CasePermissionService`,
 * the CSP nonce filter and response headers. It is the only module that grants an unrestricted
 * {@code CaseScope.all()} (enforced by ArchUnit).
 *
 * <p>Login (WP-04a): {@code SecurityConfig} requires an OIDC login for every application request and leaves Spring
 * Security's defaults on; {@code TrailkeepOidcUserService} provisions the user through
 * {@link io.trailkeep.user.UserSignInService} and yields a {@link io.trailkeep.security.TrailkeepOidcUser}.
 *
 * <p>May call: {@code shared}, {@code user}, {@code audit}. Other modules use only the {@code *Service} interfaces and
 * records in this package.
 */
@NullMarked
@ApplicationModule(displayName = "Security")
package io.trailkeep.security;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
