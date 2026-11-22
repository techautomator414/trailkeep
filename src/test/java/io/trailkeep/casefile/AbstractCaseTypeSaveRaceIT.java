// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseConfigService.Actor;
import io.trailkeep.shared.DomainException;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.UserPermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Concurrent saves of one case type, once per vendor (issue 284). One save renames the type (a lock on its row for
 * the unique-name update) and then replaces the outcomes; the other keeps the name, so it writes no row of the type,
 * deletes the outcomes first and then inserts new ones (a share lock on the type row for the foreign key). Without the
 * row lock taken first, each waits for the other and PostgreSQL aborts one as a deadlock, which was a 500. Both saves
 * must now finish, one after the other, with nothing worse than a {@code CONFLICT}, no ERROR in the log and a type
 * that holds one save's values whole.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractCaseTypeSaveRaceIT {

    private static final int ROUNDS = 40;
    private static final Actor COMPLIANCE_ADMIN =
            new Actor(new UserPermissions(41, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()), "Synthetic Admin");

    @Autowired
    private CaseConfigService config;

    @Autowired
    private JdbcTemplate jdbc;

    private final List<Long> typeIds = new ArrayList<>();
    private final List<Long> policyIds = new ArrayList<>();

    @AfterEach
    void removeCommittedRows() {
        typeIds.forEach(id -> {
            jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", id);
            jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", id);
        });
        policyIds.forEach(id -> jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", id));
    }

    @Test
    void concurrentSavesOfOneCaseTypeSerializeWithoutADeadlock(CapturedOutput output) throws Exception {
        long policy = config.savePolicy(
                COMPLIANCE_ADMIN,
                null,
                new SlaPolicyCommand(unique("P"), SlaAnchor.OPENED_AT, 10, 80, 7, EscalateTo.OWNER));
        policyIds.add(policy);
        String original = unique("Type");
        long id = config.saveCaseType(COMPLIANCE_ADMIN, null, type(original, policy, "Resolved"));
        typeIds.add(id);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                config.saveCaseType(COMPLIANCE_ADMIN, id, type(original, policy, "Resolved"));
                String renamed = unique("Renamed");
                CyclicBarrier together = new CyclicBarrier(2);
                Future<String> rename =
                        pool.submit(() -> save(together, id, type(renamed, policy, "Resolved", "Referred")));
                Future<String> keep =
                        pool.submit(() -> save(together, id, type(original, policy, "Resolved", "Closed")));
                assertThat(List.of(rename.get(60, TimeUnit.SECONDS), keep.get(60, TimeUnit.SECONDS)))
                        .as("a save is done or refused as a conflict, never a failure")
                        .allMatch(result -> result.equals("saved") || result.equals("CONFLICT"));
                String name = Objects.requireNonNull(
                        jdbc.queryForObject("SELECT NAME FROM CASE_TYPE WHERE ID = ?", String.class, id));
                List<String> outcomes = jdbc.queryForList(
                        "SELECT OUTCOME FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ? ORDER BY SORT_ORDER",
                        String.class,
                        id);
                assertThat(Map.entry(name, outcomes))
                        .as("one save's values, whole")
                        .isIn(
                                Map.entry(renamed, List.of("Resolved", "Referred")),
                                Map.entry(original, List.of("Resolved", "Closed")));
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain(" ERROR ");
    }

    private String save(CyclicBarrier together, long id, CaseTypeCommand command) throws Exception {
        together.await(30, TimeUnit.SECONDS);
        try {
            config.saveCaseType(COMPLIANCE_ADMIN, id, command);
            return "saved";
        } catch (DomainException refused) {
            return refused.kind() == Kind.CONFLICT ? "CONFLICT" : refused.kind().name();
        }
    }

    private static CaseTypeCommand type(String name, long policy, String... outcomes) {
        return new CaseTypeCommand(name, policy, 7, true, List.of(outcomes));
    }

    private static String unique(String prefix) {
        return prefix + " " + UUID.randomUUID().toString().substring(0, 8);
    }
}
