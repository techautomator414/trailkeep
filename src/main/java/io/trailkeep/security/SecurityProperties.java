// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import io.trailkeep.user.GlobalRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Settings under {@code app.security}.
 *
 * @param groupsClaim the ID-token or user-info claim listing the user's identity-provider groups
 * @param groupRoles identity-provider group name to the global role it grants; groups not listed grant nothing.
 *     Team roles never come from the provider (BUILD_PLAN §3.1).
 * @param absoluteSessionLifetime how long a login lasts however active the user is (BUILD_PLAN §9: 8 hours); the
 *     30-minute idle timeout is {@code server.servlet.session.timeout}
 */
@Validated
@ConfigurationProperties("app.security")
public record SecurityProperties(
        @NotBlank @DefaultValue("groups") String groupsClaim,
        @NotNull @DefaultValue Map<String, GlobalRole> groupRoles,
        @NotNull @DefaultValue("8h") Duration absoluteSessionLifetime) {

    /** Defensive copy. */
    public SecurityProperties {
        groupRoles = groupRoles == null ? Map.of() : Map.copyOf(groupRoles);
    }
}
