// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;

import io.trailkeep.casefile.CaseConfigService.Actor;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserPermissions;
import java.util.List;
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
 * The lost race for a unique name, once per vendor (issue 213). The service checks that a name is free before it
 * writes, and a concurrent request can take it in between; the check is made to miss here, as a request that
 * committed meanwhile would make it, so the database's unique constraint is what refuses. That refusal must be the
 * same {@code CONFLICT} the check gives (which the web layer shows as a field error), raised inside the service and
 * not as a {@code DataIntegrityViolationException} at commit, which was a 500.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractConfigNameRaceIT {

    private static final Actor COMPLIANCE_ADMIN =
            new Actor(new UserPermissions(41, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()), "Synthetic Admin");

    @Autowired
    private CaseConfigService config;

    @MockitoSpyBean
    private SlaPolicyRepository policies;

    @MockitoSpyBean
    private CaseTypeRepository caseTypes;

    @Test
    void creatingAPolicyThatLosesTheNameRaceIsAConflict() {
        String name = unique("Policy");
        config.savePolicy(COMPLIANCE_ADMIN, null, policy(name));
        doReturn(Optional.empty()).when(policies).findByName(name);

        assertThatThrownBy(() -> config.savePolicy(COMPLIANCE_ADMIN, null, policy(name)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void renamingAPolicyThatLosesTheNameRaceIsAConflict() {
        String taken = unique("Taken");
        config.savePolicy(COMPLIANCE_ADMIN, null, policy(taken));
        long other = config.savePolicy(COMPLIANCE_ADMIN, null, policy(unique("Other")));
        doReturn(Optional.empty()).when(policies).findByName(taken);

        assertThatThrownBy(() -> config.savePolicy(COMPLIANCE_ADMIN, other, policy(taken)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void creatingACaseTypeThatLosesTheNameRaceIsAConflict() {
        long policy = config.savePolicy(COMPLIANCE_ADMIN, null, policy(unique("P")));
        String name = unique("Type");
        config.saveCaseType(COMPLIANCE_ADMIN, null, type(name, policy));
        doReturn(Optional.empty()).when(caseTypes).findByName(name);

        assertThatThrownBy(() -> config.saveCaseType(COMPLIANCE_ADMIN, null, type(name, policy)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(Kind.CONFLICT));
    }

    @Test
    void renamingACaseTypeThatLosesTheNameRaceIsAConflict() {
        long policy = config.savePolicy(COMPLIANCE_ADMIN, null, policy(unique("P")));
        String taken = unique("Taken");
        config.saveCaseType(COMPLIANCE_ADMIN, null, type(taken, policy));
        long other = config.saveCaseType(COMPLIANCE_ADMIN, null, type(unique("Other"), policy));
        doReturn(Optional.empty()).when(caseTypes).findByName(taken);

        assertThatThrownBy(() -> config.saveCaseType(COMPLIANCE_ADMIN, other, type(taken, policy)))
                .isInstanceOf(DomainException.class)
                .satisfies(e -> assertThat(((DomainException) e).kind()).isEqualTo(Kind.CONFLICT));
    }

    private static SlaPolicyCommand policy(String name) {
        return new SlaPolicyCommand(name, SlaAnchor.OPENED_AT, 10, 80, 7, EscalateTo.OWNER);
    }

    private static CaseTypeCommand type(String name, long policy) {
        return new CaseTypeCommand(name, policy, 7, true, List.of("Resolved"));
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
