// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.audit.AuditRecord;
import io.trailkeep.audit.AuditTrailService;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The same person signing in for the first time from several sessions at once (issue 298), once per vendor. Each
 * sign-in runs on its own thread and transaction, released together by a latch: every one must succeed and resolve to
 * the one {@code APP_USER} row with the same roles, and the creation is audited once. The test pool is smaller than
 * the number of sign-ins, so a fix that holds one connection while it asks for a second would hang here.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractConcurrentFirstSignInIT {

    private static final int SIGN_INS = 8;
    private final List<String> subjects = new ArrayList<>();

    @Autowired
    private UserSignInService service;

    @Autowired
    private AppUserRepository users;

    @Autowired
    private AuditTrailService auditTrail;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private TransactionTemplate transactions;

    @AfterEach
    void removeCommittedRows() {
        CommittedSignIns.remove(jdbc, subjects, List.of());
    }

    @Test
    void concurrentFirstSignInsOfOnePersonAllResolveToOneUser() throws Exception {
        String subject = subject();

        List<SignInOutcome> outcomes = concurrently(subject);

        List<UserAccount> accounts = outcomes.stream()
                .map(outcome -> {
                    assertThat(outcome).isInstanceOf(SignInOutcome.SignedIn.class);
                    return ((SignInOutcome.SignedIn) outcome).account();
                })
                .toList();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, subject))
                .isEqualTo(1L);
        long id = Objects.requireNonNull(
                users.findByExternalId(subject).orElseThrow().getId());
        assertThat(accounts).hasSize(SIGN_INS).allSatisfy(account -> {
            assertThat(account.id()).isEqualTo(id);
            assertThat(account.globalRoles()).containsExactly(GlobalRole.COMPLIANCE_ADMIN);
        });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", Long.class, id))
                .isEqualTo(1L);
        List<String> actions = auditTrail.recent(CaseScope.system(), 500).stream()
                .filter(event -> Long.valueOf(id).equals(event.entityId()))
                .map(AuditRecord::action)
                .toList();
        assertThat(actions.stream().filter("USER_PROVISIONED"::equals)).hasSize(1);
        assertThat(actions.stream().filter("LOGIN"::equals)).hasSize(SIGN_INS);
        assertThat(actions.stream().filter("GLOBAL_ROLES_CHANGED"::equals)).hasSize(1);
    }

    @Test
    void concurrentSignInsOfADisabledUserAreAllRefused() throws Exception {
        String subject = subject();
        service.signIn(new ExternalIdentity(subject, "Before", CommittedSignIns.email(subject)), Set.of());
        jdbc.update("UPDATE APP_USER SET DISABLED = ? WHERE EXTERNAL_ID = ?", true, subject);

        List<SignInOutcome> outcomes = concurrently(subject);

        assertThat(outcomes)
                .hasSize(SIGN_INS)
                .allSatisfy(outcome -> assertThat(outcome).isInstanceOf(SignInOutcome.Disabled.class));
        assertThat(jdbc.queryForObject("SELECT DISABLED FROM APP_USER WHERE EXTERNAL_ID = ?", Boolean.class, subject))
                .isTrue();
    }

    @Test
    void signInRefusesToRunInsideACallersTransaction() {
        String subject = subject();
        assertThatThrownBy(() -> transactions.executeWithoutResult(status -> service.signIn(
                        new ExternalIdentity(subject, "Inside", CommittedSignIns.email(subject)), Set.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside one");
    }

    private String subject() {
        String subject = "subject-" + UUID.randomUUID();
        subjects.add(subject);
        return subject;
    }

    private List<SignInOutcome> concurrently(String subject) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(SIGN_INS);
        try {
            CountDownLatch ready = new CountDownLatch(SIGN_INS);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<SignInOutcome>> futures = new ArrayList<>();
            for (int i = 0; i < SIGN_INS; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return service.signIn(
                            new ExternalIdentity(subject, "Concurrent", CommittedSignIns.email(subject)),
                            Set.of(GlobalRole.COMPLIANCE_ADMIN));
                }));
            }
            assertThat(ready.await(30, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<SignInOutcome> outcomes = new ArrayList<>();
            for (Future<SignInOutcome> future : futures) {
                try {
                    outcomes.add(future.get(120, TimeUnit.SECONDS));
                } catch (ExecutionException failed) {
                    throw new AssertionError("a concurrent sign-in failed", failed.getCause());
                }
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }
}
