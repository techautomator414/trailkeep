// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseReportService.AgingRow;
import io.trailkeep.casefile.CaseReportService.ComplianceRow;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Criteria implementation of {@link CaseReportQueries}: one {@code GROUP BY} each over the case, its type and its SLA
 * row, with {@code SUM(CASE WHEN … THEN 1 ELSE 0 END)} per column, so a report costs one statement however many
 * cases it covers. The scope's predicate is the first condition, as in every case-rooted query; no SQL function
 * touches a CLOB, and nothing but counts, team ids and case type names leaves.
 */
class CaseReportQueriesImpl implements CaseReportQueries {

    private static final List<CaseStatus> OPEN = List.of(CaseStatus.OPEN, CaseStatus.PENDING_CLOSURE);

    private final EntityManager entityManager;

    CaseReportQueriesImpl(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    @Override
    public List<AgingRow> aging(CaseScope scope, LocalDate today) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        Grouped g = new Grouped(cb);
        Expression<LocalDate> identified = g.record.<LocalDate>get("identifiedOn");
        Expression<SlaState> state = g.sla.<SlaState>get("state");
        List<Tuple> rows = g.rows(
                scope,
                g.record.get("status").in(OPEN),
                count(cb, cb.greaterThanOrEqualTo(identified, today.minusDays(30))),
                count(cb, cb.between(identified, today.minusDays(60), today.minusDays(31))),
                count(cb, cb.between(identified, today.minusDays(90), today.minusDays(61))),
                count(cb, cb.lessThan(identified, today.minusDays(90))),
                count(cb, cb.equal(state, SlaState.ON_TRACK)),
                count(cb, cb.equal(state, SlaState.AT_RISK)),
                count(cb, cb.equal(state, SlaState.OVERDUE)),
                count(cb, cb.equal(state, SlaState.NOT_APPLICABLE)));
        return rows.stream()
                .map(row -> new AgingRow(
                        row.get(0, Long.class),
                        row.get(1, String.class),
                        number(row, 2),
                        number(row, 3),
                        number(row, 4),
                        number(row, 5),
                        number(row, 6),
                        number(row, 7),
                        number(row, 8),
                        number(row, 9)))
                .toList();
    }

    @Override
    public List<ComplianceRow> compliance(CaseScope scope, Instant from, Instant until) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        Grouped g = new Grouped(cb);
        Expression<Instant> closedAt = g.record.<Instant>get("closedAt");
        Expression<SlaState> state = g.sla.<SlaState>get("state");
        Predicate closed = cb.and(
                cb.equal(g.record.get("status"), CaseStatus.CLOSED),
                cb.greaterThanOrEqualTo(closedAt, from),
                cb.lessThan(closedAt, until));
        Predicate open = g.record.get("status").in(OPEN);
        List<Tuple> rows = g.rows(
                scope,
                cb.or(closed, open),
                count(cb, cb.and(closed, cb.equal(state, SlaState.MET))),
                count(cb, cb.and(closed, cb.equal(state, SlaState.BREACHED))),
                count(cb, cb.and(open, cb.equal(state, SlaState.AT_RISK))),
                count(cb, cb.and(open, cb.equal(state, SlaState.OVERDUE))));
        return rows.stream()
                .map(row -> new ComplianceRow(
                        row.get(0, Long.class),
                        row.get(1, String.class),
                        number(row, 2),
                        number(row, 3),
                        number(row, 4),
                        number(row, 5)))
                .toList();
    }

    private static Expression<Integer> count(CriteriaBuilder cb, Predicate when) {
        return cb.sum(cb.<Integer>selectCase().when(when, 1).otherwise(0));
    }

    private static long number(Tuple row, int column) {
        Number value = row.get(column, Number.class);
        return value == null ? 0 : value.longValue();
    }

    /** One grouped report query: the case, its type and its SLA row, by team and type. */
    private final class Grouped {

        private final CriteriaBuilder cb;
        private final CriteriaQuery<Tuple> query;
        private final Root<CaseRecord> record;
        private final Root<CaseType> type;
        private final Root<CaseSla> sla;

        Grouped(CriteriaBuilder cb) {
            this.cb = cb;
            this.query = cb.createTupleQuery();
            this.record = query.from(CaseRecord.class);
            this.type = query.from(CaseType.class);
            this.sla = query.from(CaseSla.class);
        }

        /** Runs the query: the team, the type's name, then the counts. */
        List<Tuple> rows(CaseScope scope, Predicate which, Expression<?>... counts) {
            List<Selection<?>> columns = new ArrayList<>(List.of(record.get("teamId"), type.get("name")));
            columns.addAll(List.of(counts));
            query.select(cb.tuple(columns.toArray(Selection<?>[]::new)))
                    .where(
                            scope.toPredicate(record, query, cb),
                            cb.equal(type.get("id"), record.get("caseTypeId")),
                            cb.equal(sla.get("caseId"), record.get("id")),
                            which)
                    .groupBy(record.get("teamId"), type.get("name"))
                    .orderBy(cb.asc(record.get("teamId")), cb.asc(type.get("name")));
            return entityManager.createQuery(query).getResultList();
        }
    }
}
