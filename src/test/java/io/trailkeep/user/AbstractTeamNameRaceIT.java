// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamAdminService.Actor;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * The lost race for a team name, once per vendor (issue 213): the check for a free name is made to miss, as a request
 * that committed meanwhile would make it, and the unique constraint must be answered with the same {@code CONFLICT}
 * the check gives, inside the service, not as a {@code DataIntegrityViolationException} at commit (a 500).
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractTeamNameRaceIT {

    private static final Actor SYSTEM_ADMIN =
            new Actor(new UserPermissions(51, false, Set.of(GlobalRole.SYSTEM_ADMIN), Map.of()), "Synthetic Admin");

    @Autowired
    private TeamAdminService admin;

    @MockitoSpyBean
    private TeamRepository teams;

    @Test
    void creatingATeamThatLosesTheNameRaceIsAConflict() {
        String name = unique("Team");
        admin.createTeam(SYSTEM_ADMIN, name);
        doReturn(Optional.empty()).when(teams).findByName(name);

        assertThatThrownBy(() -> admin.createTeam(SYSTEM_ADMIN, name))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void renamingATeamThatLosesTheNameRaceIsAConflict() {
        String taken = unique("Taken");
        admin.createTeam(SYSTEM_ADMIN, taken);
        long other = admin.createTeam(SYSTEM_ADMIN, unique("Other"));
        doReturn(Optional.empty()).when(teams).findByName(taken);

        assertThatThrownBy(() -> admin.updateTeam(SYSTEM_ADMIN, other, taken, true))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(Kind.CONFLICT));
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
