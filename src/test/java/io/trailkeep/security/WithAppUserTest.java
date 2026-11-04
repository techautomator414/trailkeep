// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(WithAppUserTest.Empty.class)
class WithAppUserTest {

    static class Empty {}

    @Test
    @WithAppUser(id = 42, team = 7, teamRole = TeamRole.ANALYST)
    void teamUserHasTeamRoleAndNoAuthorities() {
        TrailkeepOidcUser user = principal();

        assertThat(user.getName()).isEqualTo("42");
        assertThat(user.userId()).isEqualTo(42L);
        assertThat(user.getAuthorities()).isEmpty();
        assertThat(permissions().teamRoles()).containsExactlyEntriesOf(Map.of(7L, TeamRole.ANALYST));
        assertThat(permissions().globalRoles()).isEmpty();
        assertThat(authentication().getAuthorities()).isEmpty();
    }

    @Test
    @WithAppUser(globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void globalRolesBecomeRoleAuthorities() {
        Authentication authentication = authentication();

        assertThat(authentication).isInstanceOf(OAuth2AuthenticationToken.class);
        assertThat(((OAuth2AuthenticationToken) authentication).getAuthorizedClientRegistrationId())
                .isEqualTo("idp");
        assertThat(authentication.getAuthorities())
                .extracting(GrantedAuthority::getAuthority)
                .containsExactly("ROLE_COMPLIANCE_ADMIN");
        assertThat(permissions().teamRoles()).isEmpty();
        assertThat(permissions().has(GlobalRole.COMPLIANCE_ADMIN)).isTrue();
    }

    private static Authentication authentication() {
        return Objects.requireNonNull(SecurityContextHolder.getContext().getAuthentication());
    }

    private static UserPermissions permissions() {
        return (UserPermissions) Objects.requireNonNull(authentication().getDetails());
    }

    private static TrailkeepOidcUser principal() {
        return (TrailkeepOidcUser) Objects.requireNonNull(authentication().getPrincipal());
    }
}
