// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseScope;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Tuple;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Expression;
import jakarta.persistence.criteria.Join;
import jakarta.persistence.criteria.JoinType;
import jakarta.persistence.criteria.Nulls;
import jakarta.persistence.criteria.Order;
import jakarta.persistence.criteria.Path;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Selection;
import jakarta.persistence.criteria.Subquery;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Criteria implementation of {@link CaseRecordQueries}. Every query is one statement whose {@code WHERE} holds the
 * case number and {@link CaseScope#toPredicate}; {@code CaseRecord} has no associations, so there is no graph to
 * fetch, and the case page's row joins the case type and SLA row as entities, by id. The list's count and page share
 * one {@code WHERE}: the scope, the filter and, for a search, the {@link SearchGateway}'s predicate.
 */
class CaseRecordQueriesImpl implements CaseRecordQueries {

    private final EntityManager entityManager;
    private final SearchGateway search;

    CaseRecordQueriesImpl(EntityManager entityManager, SearchGateway search) {
        this.entityManager = entityManager;
        this.search = search;
    }

    @Override
    public Optional<CaseRecord> findOne(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseRecord> query = cb.createQuery(CaseRecord.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(root)
                .where(cb.equal(root.get("caseNumber"), caseNumber.toString()), scope.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getResultStream().findFirst();
    }

    @Override
    public Optional<CaseRecord> findById(CaseScope scope, long caseId) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseRecord> query = cb.createQuery(CaseRecord.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(root).where(cb.equal(root.get("id"), caseId), scope.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getResultStream().findFirst();
    }

    @Override
    public boolean exists(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(cb.count(root))
                .where(cb.equal(root.get("caseNumber"), caseNumber.toString()), scope.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getSingleResult() > 0;
    }

    @Override
    public Optional<CaseRow> findRow(CaseScope scope, CaseNumber caseNumber) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseRow> query = cb.createQuery(CaseRow.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        Join<CaseRecord, CaseType> type = root.join(CaseType.class);
        type.on(cb.equal(type.get("id"), root.get("caseTypeId")));
        Join<CaseRecord, CaseSla> sla = root.join(CaseSla.class, JoinType.LEFT);
        sla.on(cb.equal(sla.get("caseId"), root.get("id")));
        query.select(cb.construct(CaseRow.class, root, type.get("name"), sla.get("state"), sla.get("dueAt")))
                .where(cb.equal(root.get("caseNumber"), caseNumber.toString()), scope.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getResultStream().findFirst();
    }

    @Override
    public long count(CaseScope scope, CaseFilter filter) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Long> query = cb.createQuery(Long.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(cb.count(root)).where(where(scope, filter, root, query, cb));
        return entityManager.createQuery(query).getSingleResult();
    }

    @Override
    public List<CaseListRow> list(CaseScope scope, CaseListQuery request, int pageSize) {
        return page(scope, request, pageSize, entityManager.getCriteriaBuilder().createQuery(CaseListRow.class));
    }

    @Override
    public ExportPage export(CaseScope scope, CaseListQuery request, @Nullable ExportCursor after, int pageSize) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseRecord> root = query.from(CaseRecord.class);
        Join<CaseRecord, CaseType> caseType = root.join(CaseType.class);
        caseType.on(cb.equal(caseType.get("id"), root.get("caseTypeId")));
        Join<CaseRecord, CaseSla> sla = root.join(CaseSla.class, JoinType.LEFT);
        sla.on(cb.equal(sla.get("caseId"), root.get("id")));
        Expression<?> key = sortKey(request.sort(), root, sla, cb);
        // The subject and the description are selected as they are, with no function applied: the description is a
        // CLOB on Oracle. The last two columns are the keyset cursor: the sort key as the database computed it, and the
        // id.
        query.select(cb.tuple(
                        root.get("caseNumber"),
                        root.get("title"),
                        root.get("status"),
                        root.get("sensitivity"),
                        root.get("priority"),
                        caseType.get("name"),
                        root.get("teamId"),
                        root.get("ownerId"),
                        root.get("openedAt"),
                        sla.get("state"),
                        sla.get("dueAt"),
                        root.get("subjectText"),
                        root.get("description"),
                        key,
                        root.get("id")))
                .where(Stream.concat(
                                Arrays.stream(where(scope, request.filter(), root, query, cb)),
                                Stream.ofNullable(after).map(cursor -> following(request, key, root, cursor, cb)))
                        .toArray(Predicate[]::new))
                .orderBy(order(request, key, cb), cb.asc(root.get("id")));
        List<Tuple> tuples =
                entityManager.createQuery(query).setMaxResults(pageSize).getResultList();
        List<CaseExportRow> rows =
                tuples.stream().map(CaseRecordQueriesImpl::exportRow).toList();
        Tuple last = tuples.isEmpty() ? null : tuples.get(tuples.size() - 1);
        return new ExportPage(rows, last == null ? null : new ExportCursor(last.get(13), last.get(14, Long.class)));
    }

    private static CaseExportRow exportRow(Tuple t) {
        return new CaseExportRow(
                t.get(0, String.class),
                t.get(1, String.class),
                t.get(2, CaseStatus.class),
                t.get(3, Sensitivity.class),
                t.get(4, CasePriority.class),
                t.get(5, String.class),
                t.get(6, Long.class),
                t.get(7, Long.class),
                t.get(8, Instant.class),
                t.get(9, SlaState.class),
                t.get(10, Instant.class),
                t.get(11, String.class),
                t.get(12, String.class));
    }

    /**
     * The rows strictly after a cursor in the list's order: the sort key in its direction, then the id ascending, and
     * the cases without a due instant last in both directions (the {@code NULLS LAST} of {@link #order}). Written as
     * comparisons and an {@code OR}, not as a row-value comparison, which Oracle does not take in every context, and
     * it handles a null key on its own: once the cursor is in the null block, only a later id of that block remains.
     * The key is compared as the database computed it (the value the cursor carries came from the same expression),
     * so the collation that ordered the rows also decides "after".
     */
    @SuppressWarnings({"unchecked", "rawtypes"}) // the key is a column or expression of one Comparable type per sort
    private static Predicate following(
            CaseListQuery request, Expression<?> key, Root<CaseRecord> root, ExportCursor cursor, CriteriaBuilder cb) {
        Expression<Comparable> sortKey = (Expression<Comparable>) key;
        Predicate laterId = cb.greaterThan(root.<Long>get("id"), cursor.id());
        if (cursor.key() == null) {
            return cb.and(cb.isNull(sortKey), laterId);
        }
        Comparable value = (Comparable) cursor.key();
        Predicate beyond = request.descending() ? cb.lessThan(sortKey, value) : cb.greaterThan(sortKey, value);
        Predicate tie = cb.and(cb.equal(sortKey, value), laterId);
        // Only DUE has a nullable key; the null block comes after every value.
        return request.sort() == CaseSort.DUE ? cb.or(beyond, tie, cb.isNull(sortKey)) : cb.or(beyond, tie);
    }

    /** One page of the list's projection. */
    private <T> List<T> page(CaseScope scope, CaseListQuery request, int pageSize, CriteriaQuery<T> query) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        Root<CaseRecord> root = query.from(CaseRecord.class);
        Join<CaseRecord, CaseType> caseType = root.join(CaseType.class);
        caseType.on(cb.equal(caseType.get("id"), root.get("caseTypeId")));
        Join<CaseRecord, CaseSla> sla = root.join(CaseSla.class, JoinType.LEFT);
        sla.on(cb.equal(sla.get("caseId"), root.get("id")));
        List<Selection<?>> columns = List.of(
                root.get("caseNumber"),
                root.get("title"),
                root.get("status"),
                root.get("sensitivity"),
                root.get("priority"),
                caseType.get("name"),
                root.get("teamId"),
                root.get("ownerId"),
                root.get("openedAt"),
                sla.get("state"),
                sla.get("dueAt"));
        query.select(cb.construct(query.getResultType(), columns.toArray(new Selection<?>[0])))
                .where(where(scope, request.filter(), root, query, cb))
                .orderBy(order(request, sortKey(request.sort(), root, sla, cb), cb), cb.asc(root.get("id")));
        long offset = (long) (request.page() - 1) * pageSize;
        return entityManager
                .createQuery(query)
                .setFirstResult((int) Math.min(offset, Integer.MAX_VALUE))
                .setMaxResults(pageSize)
                .getResultList();
    }

    @Override
    public List<CaseListRow.TeamOwner> teamsAndOwners(CaseScope scope) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<CaseListRow.TeamOwner> query = cb.createQuery(CaseListRow.TeamOwner.class);
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(cb.construct(CaseListRow.TeamOwner.class, root.get("teamId"), root.get("ownerId")))
                .distinct(true)
                .where(scope.toPredicate(root, query, cb));
        return entityManager.createQuery(query).getResultList();
    }

    @Override
    public List<CaseService.CaseRef> refs(CaseScope scope, Collection<Long> caseIds) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(cb.tuple(root.get("id"), root.get("caseNumber"), root.get("title"), root.get("teamId")))
                .where(root.get("id").in(caseIds), scope.toPredicate(root, query, cb));
        return entityManager
                .createQuery(query)
                .getResultStream()
                .map(row -> new CaseService.CaseRef(
                        row.get(0, Long.class),
                        CaseNumber.parse(row.get(1, String.class)),
                        row.get(2, String.class),
                        row.get(3, Long.class)))
                .toList();
    }

    @Override
    public List<CaseImportService.Existing> importStates(CaseScope scope, Collection<Long> caseIds) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Tuple> query = cb.createTupleQuery();
        Root<CaseRecord> root = query.from(CaseRecord.class);
        query.select(cb.tuple(
                        root.get("id"),
                        root.get("caseNumber"),
                        root.get("archivedAt"),
                        root.get("purgedAt"),
                        root.get("version"),
                        root.get("createdAt"),
                        root.get("updatedAt"),
                        root.get("updatedBy")))
                .where(root.get("id").in(caseIds), scope.toPredicate(root, query, cb));
        return entityManager
                .createQuery(query)
                .getResultStream()
                .map(row -> new CaseImportService.Existing(
                        new CaseImportService.Created(
                                row.get(0, Long.class), CaseNumber.parse(row.get(1, String.class))),
                        row.get(3) != null
                                ? CaseImportService.Existing.State.PURGED
                                : row.get(2) != null
                                        ? CaseImportService.Existing.State.ARCHIVED
                                        : CaseImportService.Existing.State.ACTIVE,
                        // An import creates a case at version 0 with equal stamps; every later change moves both on.
                        row.get(4, Integer.class) == 0
                                        && row.get(5, Instant.class).equals(row.get(6, Instant.class))
                                ? null
                                : row.get(7, Long.class)))
                .toList();
    }

    /** The scope and the filter, as one conjunction; archived cases only when a status filter names them. */
    private Predicate[] where(
            CaseScope scope, CaseFilter filter, Root<CaseRecord> root, CriteriaQuery<?> query, CriteriaBuilder cb) {
        if (filter.mine()) {
            throw new IllegalArgumentException("resolve \"mine\" to an owner before querying");
        }
        List<Predicate> where = new ArrayList<>(12);
        where.add(scope.toPredicate(root, query, cb));
        where.add(
                filter.statuses().isEmpty()
                        ? cb.notEqual(root.get("status"), CaseStatus.ARCHIVED)
                        : root.get("status").in(filter.statuses()));
        if (!filter.slaStates().isEmpty()) {
            where.add(root.get("id").in(slaRowsIn(filter.slaStates(), query)));
        }
        equalIfSet(where, cb, root.get("priority"), filter.priority());
        equalIfSet(where, cb, root.get("sensitivity"), filter.sensitivity());
        LocalDate from = filter.identifiedFrom();
        if (from != null) {
            where.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("identifiedOn"), from));
        }
        LocalDate to = filter.identifiedTo();
        if (to != null) {
            where.add(cb.lessThanOrEqualTo(root.<LocalDate>get("identifiedOn"), to));
        }
        equalIfSet(where, cb, root.get("caseTypeId"), filter.caseTypeId());
        equalIfSet(where, cb, root.get("teamId"), filter.teamId());
        equalIfSet(where, cb, root.get("ownerId"), filter.ownerId());
        if (!filter.numbers().isEmpty()) {
            where.add(root.get("caseNumber").in(filter.numbers()));
        }
        if (!filter.text().isEmpty()) {
            where.add(search.matches(filter.text(), root, query, cb));
        }
        return where.toArray(Predicate[]::new);
    }

    /**
     * The ids of cases whose SLA row is in one of the states, as a subquery, so the count needs no join and shares
     * the list's {@code WHERE}.
     */
    private static Subquery<Long> slaRowsIn(Set<SlaState> states, CriteriaQuery<?> query) {
        Subquery<Long> ids = query.subquery(Long.class);
        Root<CaseSla> sla = ids.from(CaseSla.class);
        return ids.select(sla.get("caseId")).where(sla.get("state").in(states));
    }

    private static void equalIfSet(List<Predicate> where, CriteriaBuilder cb, Path<?> path, @Nullable Object value) {
        if (value != null) {
            where.add(cb.equal(path, value));
        }
    }

    /** The allow-listed sort columns, each one expression (Part B "Web"). */
    private static Expression<?> sortKey(
            CaseSort sort, Root<CaseRecord> root, Join<CaseRecord, CaseSla> sla, CriteriaBuilder cb) {
        return switch (sort) {
            case NUMBER -> root.get("caseNumber");
            case TITLE -> cb.lower(root.<String>get("title"));
            case PRIORITY ->
                cb.<Integer>selectCase()
                        .when(cb.equal(root.get("priority"), CasePriority.HIGH), 0)
                        .otherwise(1);
            case DUE -> sla.get("dueAt");
            case OPENED -> root.get("openedAt");
        };
    }

    /** Cases without an SLA due instant come last in both directions. */
    private static Order order(CaseListQuery request, Expression<?> key, CriteriaBuilder cb) {
        return request.descending() ? cb.desc(key, Nulls.LAST) : cb.asc(key, Nulls.LAST);
    }
}
