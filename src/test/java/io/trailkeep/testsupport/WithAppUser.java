// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.testsupport;

import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.security.test.context.support.WithSecurityContext;

/**
 * Runs a test as a signed-in Trailkeep user: an {@code OAuth2AuthenticationToken} whose principal is a
 * {@link io.trailkeep.security.TrailkeepOidcUser}, exactly what an OIDC login produces. It sets the security context
 * only; it does not create database rows.
 *
 * <pre>{@code @WithAppUser(team = "Alpha", teamRole = TeamRole.ANALYST)}</pre>
 * <pre>{@code @WithAppUser(globalRole = GlobalRole.COMPLIANCE_ADMIN)}</pre>
 */
@Documented
@Inherited
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
@WithSecurityContext(factory = WithAppUserSecurityContextFactory.class)
public @interface WithAppUser {

    /**
     * {@code APP_USER.ID} of the user.
     *
     * @return the id
     */
    long id() default 1L;

    /**
     * Display name.
     *
     * @return the name
     */
    String name() default "Test User";

    /**
     * Team the user belongs to; empty for none.
     *
     * @return the team name
     */
    String team() default "";

    /**
     * The user's role in {@link #team()}; ignored when the team is empty.
     *
     * @return the team role
     */
    TeamRole teamRole() default TeamRole.READER;

    /**
     * Global roles in effect.
     *
     * @return the global roles
     */
    GlobalRole[] globalRole() default {};
}
