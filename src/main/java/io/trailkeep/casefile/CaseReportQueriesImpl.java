// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.casefile.CaseReportService.AgingRow;
import io.trailkeep.casefile.CaseReportService.ComplianceRow;
import io.trailkeep.casefile.CaseReportService.OverdueCase;
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
import java.util.function.BiFunction;

/**
 * Criteria implementation of {@link CaseReportQueries}: one {@code GROUP BY} each over the case, its type and its SLA
 * row (aging by team and owner, compliance by team and type), with {@code SUM(CASE WHEN … THEN 1 ELSE 0 END)} per
 * column, so a report costs one statement however many cases it covers. The scope's predicate is the first condition,
 * as in every case-rooted query; no SQL function touches a CLOB, and nothing but counts, team and owner ids and case
 * type names leaves.
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
        Grouped g = new Grouped(cb, true);
        Expression<LocalDate> identified = g.record.<LocalDate>get("identifiedOn");
        Expression<SlaState> state = g.sla.<SlaState>get("state");
        return g.rows(
                scope,
                g.record.get("status").in(OPEN),
                (row, n) -> new AgingRow(n[0], n[1], n[2], n[3], n[4], n[5], n[6], n[7], n[8], n[9]),
                count(cb, cb.greaterThanOrEqualTo(identified, today.minusDays(30))),
                count(cb, cb.between(identified, today.minusDays(60), today.minusDays(31))),
                count(cb, cb.between(identified, today.minusDays(90), today.minusDays(61))),
                count(cb, cb.lessThan(identified, today.minusDays(90))),
                count(cb, cb.equal(state, SlaState.ON_TRACK)),
                count(cb, cb.equal(state, SlaState.AT_RISK)),
                count(cb, cb.equal(state, SlaState.OVERDUE)),
                count(cb, cb.equal(state, SlaState.NOT_APPLICABLE)));
    }

    @Override
    public List<ComplianceRow> compliance(CaseScope scope, Instant from, Instant until) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        Grouped g = new Grouped(cb, false);
        Expression<Instant> closedAt = g.record.<Instant>get("closedAt");
        Expression<SlaState> state = g.sla.<SlaState>get("state");
        Predicate closed = cb.and(
                cb.equal(g.record.get("status"), CaseStatus.CLOSED),
                cb.greaterThanOrEqualTo(closedAt, from),
                cb.lessThan(closedAt, until));
        Predicate open = g.record.get("status").in(OPEN);
        return g.rows(
                scope,
                cb.or(closed, open),
                (row, n) -> new ComplianceRow(n[0], row.get(1, String.class), n[2], n[3], n[4], n[5]),
                count(cb, cb.and(closed, cb.equal(state, SlaState.MET))),
                count(cb, cb.and(closed, cb.equal(state, SlaState.BREACHED))),
                count(cb, cb.and(open, cb.equal(state, SlaState.AT_RISK))),
                count(cb, cb.and(open, cb.equal(state, SlaState.OVERDUE))));
    }

    @Override
    public List<OverdueCase> overdue(CaseScope scope, int limit) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseRecord> record = query.from(CaseRecord.class);
        Root<CaseSla> sla = query.from(CaseSla.class);
        query.select(cb.tuple(record.get("caseNumber"), record.get("title"), record.get("ownerId"), sla.get("dueAt")))
                .where(
                        scope.toPredicate(record, query, cb),
                        cb.equal(sla.get("caseId"), record.get("id")),
                        record.get("status").in(OPEN),
                        cb.equal(sla.get("state"), SlaState.OVERDUE))
                .orderBy(cb.asc(sla.get("dueAt")), cb.asc(record.get("id")));
        return entityManager.createQuery(query).setMaxResults(limit).getResultList().stream()
                .map(row -> new OverdueCase(
                        row.get(0, String.class),
                        row.get(1, String.class),
                        row.get(2, Long.class),
                        row.get(3, Instant.class)))
                .toList();
    }

    private static Expression<Integer> count(CriteriaBuilder cb, Predicate when) {
        return cb.sum(cb.<Integer>selectCase().when(when, 1).otherwise(0));
    }

    /** The row's numeric columns by position (the team id, an owner id, the counts); anything else is 0. */
    private static long[] numbers(Tuple row) {
        long[] numbers = new long[row.getElements().size()];
        for (int column = 0; column < numbers.length; column++) {
            Object value = row.get(column);
            numbers[column] = value instanceof Number number ? number.longValue() : 0;
        }
        return numbers;
    }

    /** One grouped report query: the case, its type and its SLA row, by team and then owner or case type. */
    private final class Grouped {

        private final CriteriaBuilder cb;
        private final CriteriaQuery<Tuple> query;
        private final Root<CaseRecord> record;
        private final Root<CaseType> type;
        private final Root<CaseSla> sla;
        private final Expression<?> by;

        Grouped(CriteriaBuilder cb, boolean byOwner) {
            this.cb = cb;
            this.query = cb.createTupleQuery();
            this.record = query.from(CaseRecord.class);
            this.type = query.from(CaseType.class);
            this.sla = query.from(CaseSla.class);
            this.by = byOwner ? record.get("ownerId") : type.get("name");
        }

        /** Runs the query, selecting the team, {@code by}, then the counts, and maps each row with its numbers. */
        <T> List<T> rows(
                CaseScope scope, Predicate which, BiFunction<Tuple, long[], T> mapper, Expression<?>... counts) {
            List<Selection<?>> columns = new ArrayList<>(List.of(record.get("teamId"), by));
            columns.addAll(List.of(counts));
            query.select(cb.tuple(columns.toArray(Selection<?>[]::new)))
                    .where(
                            scope.toPredicate(record, query, cb),
                            cb.equal(type.get("id"), record.get("caseTypeId")),
                            cb.equal(sla.get("caseId"), record.get("id")),
                            which)
                    .groupBy(record.get("teamId"), by)
                    .orderBy(cb.asc(record.get("teamId")), cb.asc(by));
            return entityManager.createQuery(query).getResultList().stream()
                    .map(row -> mapper.apply(row, numbers(row)))
                    .toList();
        }
    }
}
