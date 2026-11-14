// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static io.trailkeep.casefile.FilterBuilder.filter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.SavedViewService.Created;
import io.trailkeep.casefile.SavedViewService.Problem;
import io.trailkeep.casefile.SavedViewService.Refused;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.UserPermissions;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * The lost race for a saved view's name, once per vendor (issue 215). The check "does this person already have a view
 * of that name?" is made to miss, as a concurrent save that committed meanwhile would make it, so the database's
 * unique constraint refuses the write. Saving returns a {@code Refused} rather than throwing, so the refusal must be
 * the same {@code NAME_TAKEN} the check gives, and the transaction must roll back cleanly: the service's own
 * transaction is the outermost one here (the test does not wrap it), so a rollback-only transaction that was then
 * committed would surface as an {@code UnexpectedRollbackException}, a 500. Rows are committed, so they are removed
 * afterwards as the schema owner.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractSavedViewRaceIT {

    @Autowired
    private SavedViewService views;

    @MockitoSpyBean
    private SavedViewRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    private long person;
    private Actor owner;

    @BeforeEach
    void seed() {
        person = new CaseSeed(jdbc).user();
        owner = new Actor(new UserPermissions(person, false, Set.of(), Map.of()), "Synthetic View Owner");
    }

    @AfterEach
    void removeCommittedRows() {
        // Test-only cleanup as the schema owner; the application user has no DELETE grant on AUDIT_EVENT.
        jdbc.update("DELETE FROM SAVED_VIEW WHERE USER_ID = ?", person);
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ACTOR_ID = ?", person);
        jdbc.update("DELETE FROM APP_USER WHERE ID = ?", person);
    }

    @Test
    void saveThatLosesTheNameRaceIsTheSameRefusalAsTheCheckAndRollsBackCleanly() {
        CaseListQuery query = new CaseListQuery(filter().text("heron").build(), CaseSort.OPENED, true, 1);
        assertThat(views.save(owner, "Herons", query)).isInstanceOf(Created.class);
        doReturn(false).when(repository).existsByUserIdAndName(eq(person), eq("Herons"));

        assertThat(views.save(owner, "Herons", query)).isEqualTo(new Refused(Problem.NAME_TAKEN));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SAVED_VIEW WHERE USER_ID = ?", Long.class, person))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTOR_ID = ? AND ACTION = 'SAVED_VIEW_CREATED'",
                        Long.class,
                        person))
                .isEqualTo(1);
    }
}
