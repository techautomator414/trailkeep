// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import io.trailkeep.audit.AuditTrailService.AuditFilter;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

/** The case-rooted audit repository through {@link AuditTrailService}, on PostgreSQL. */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class AuditTrailServiceIT {

    private static final long CASE_A = 100L;
    private static final long CASE_B = 200L;

    /** A principal-style scope that reads no case: what a Reader of another team holds. */
    private static final CaseScope NOTHING_READABLE = new CaseScope() {
        @Override
        public Predicate toPredicate(Root<?> caseRoot, CriteriaQuery<?> query, CriteriaBuilder cb) {
            return cb.disjunction();
        }

        @Override
        public boolean canRead(CaseNumber caseNumber) {
            return false;
        }
    };

    @Autowired
    private AuditTrailService trail;

    @Autowired
    private AuditRecorder recorder;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM AUDIT_EVENT");
        transactions.executeWithoutResult(status -> {
            recorder.record(entry(null, "LOGIN"));
            recorder.record(entry(CASE_A, "CREATE"));
            recorder.record(entry(CASE_B, "CREATE"));
            recorder.record(entry(CASE_A, "UPDATE"));
        });
    }

    /** Committed events with fixed case ids would collide with real case ids in later tests. */
    @AfterEach
    void removeEvents() {
        jdbc.update("DELETE FROM AUDIT_EVENT");
    }

    @Test
    void caselessEventsAreVisibleThroughTheAllScope() {
        assertThat(trail.recent(CaseScope.all(), 10))
                .hasSize(4)
                .anySatisfy(event -> assertThat(event.caseId()).isNull());
    }

    @Test
    void theSystemScopeSeesEveryEvent() {
        assertThat(trail.recent(CaseScope.system(), 10)).hasSize(4);
    }

    @Test
    void caseTimelineHoldsOnlyThatCaseNewestFirst() {
        assertThat(trail.forCase(CaseScope.system(), CASE_A, 10))
                .extracting(AuditRecord::action)
                .containsExactly("UPDATE", "CREATE");
    }

    @Test
    void restrictedScopeSeesNothingRatherThanTooMuch() {
        assertThat(trail.recent(NOTHING_READABLE, 10)).isEmpty();
        assertThat(trail.forCase(NOTHING_READABLE, CASE_A, 10)).isEmpty();
        assertThat(trail.forCase(NOTHING_READABLE, 999L, 10))
                .as("unreadable and absent cases are indistinguishable")
                .isEqualTo(trail.forCase(NOTHING_READABLE, CASE_A, 10));
    }

    @Test
    void pageSkipsFiltersAndCountsNewestFirst() {
        AuditTrailService.AuditPage all = trail.pageForCase(CaseScope.system(), CASE_A, Set.of(), 0, 10);
        assertThat(all.total()).isEqualTo(2);
        assertThat(all.records()).extracting(AuditRecord::action).containsExactly("UPDATE", "CREATE");

        AuditTrailService.AuditPage second = trail.pageForCase(CaseScope.system(), CASE_A, Set.of(), 1, 1);
        assertThat(second.total()).as("the total does not depend on the page").isEqualTo(2);
        assertThat(second.records()).extracting(AuditRecord::action).containsExactly("CREATE");

        AuditTrailService.AuditPage creates = trail.pageForCase(CaseScope.system(), CASE_A, Set.of("CREATE"), 0, 10);
        assertThat(creates.total()).isEqualTo(1);
        assertThat(creates.records()).extracting(AuditRecord::action).containsExactly("CREATE");
        assertThat(trail.pageForCase(CaseScope.system(), CASE_A, Set.of(), 5, 10)
                        .records())
                .as("a page past the end is empty")
                .isEmpty();
    }

    @Test
    void pageOfUnreadableOrAbsentCaseIsEmptyWithTotalOfZero() {
        assertThat(trail.pageForCase(NOTHING_READABLE, CASE_A, Set.of(), 0, 10).total())
                .isZero();
        assertThat(trail.pageForCase(NOTHING_READABLE, CASE_A, Set.of(), 0, 10).records())
                .isEmpty();
        assertThat(trail.pageForCase(CaseScope.system(), 999L, Set.of(), 0, 10).total())
                .isZero();
        assertThatThrownBy(() -> trail.pageForCase(CaseScope.system(), CASE_A, Set.of(), -1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trail.pageForCase(CaseScope.system(), CASE_A, Set.of(), 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theLimitIsAppliedAndBounded() {
        assertThat(trail.recent(CaseScope.system(), 2)).hasSize(2);
        assertThatThrownBy(() -> trail.recent(CaseScope.system(), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> trail.recent(CaseScope.system(), DefaultAuditTrailService.MAX_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theSearchReachesCaselessEventsOnlyThroughAnUnrestrictedScopeAndAppliesEveryFilter() {
        transactions.executeWithoutResult(status -> recorder.record(new AuditEntry(
                8L, "Synthetic 50%_Off Person", AuditOrigin.UI, null, "USER", 8L, "LOGIN", AuditChanges.none())));
        Instant now = Instant.now();
        Instant hourAgo = now.minus(Duration.ofHours(1));
        Instant inAnHour = now.plus(Duration.ofHours(1));

        assertThat(search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, null, null, null, null), 0)
                        .total())
                .isEqualTo(5);
        assertThat(search(NOTHING_READABLE, new AuditFilter(hourAgo, inAnHour, null, null, null, null), 0))
                .as("a principal's scope reaches nothing, not even its own cases' events")
                .isEqualTo(new AuditTrailService.AuditPage(List.of(), 0));
        assertThat(search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, "50%_OFF", null, null, null), 0)
                        .records())
                .extracting(AuditRecord::actorName)
                .containsExactly("Synthetic 50%_Off Person");
        assertThat(search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, "5_%", null, null, null), 0)
                        .total())
                .as("wildcards in the name are matched as text")
                .isZero();
        assertThat(search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, null, "USER", null, null), 0)
                        .total())
                .isEqualTo(1);
        assertThat(search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, null, null, "CREATE", null), 0)
                        .total())
                .isEqualTo(2);
        assertThat(search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, null, null, "UPDATE", CASE_A), 0)
                        .records())
                .extracting(AuditRecord::caseId, AuditRecord::action)
                .containsExactly(tuple(CASE_A, "UPDATE"));
        AuditFilter before = new AuditFilter(hourAgo.minusSeconds(3600), hourAgo, null, null, null, null);
        assertThat(search(CaseScope.all(), before, 0).total())
                .as("the time range bounds every search")
                .isZero();

        AuditTrailService.AuditPage second =
                trail.search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, null, null, null, null), 1, 2);
        assertThat(second.total()).isEqualTo(5);
        assertThat(second.records()).extracting(AuditRecord::action).containsExactly("UPDATE", "CREATE");
        assertThat(search(CaseScope.all(), new AuditFilter(hourAgo, inAnHour, null, null, null, null), 10)
                        .records())
                .isEmpty();
        assertThatThrownBy(() -> new AuditFilter(now, now, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuditFilter(
                        now, now.plus(AuditTrailService.MAX_RANGE).plusSeconds(1), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private AuditTrailService.AuditPage search(CaseScope scope, AuditFilter filter, int offset) {
        return trail.search(scope, filter, offset, 10);
    }

    private static AuditEntry entry(@Nullable Long caseId, String action) {
        return new AuditEntry(
                7L, "Synthetic Admin", AuditOrigin.UI, caseId, "CASE", caseId, action, AuditChanges.none());
    }
}
