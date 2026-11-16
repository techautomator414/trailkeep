<!-- SPDX-License-Identifier: Apache-2.0 -->
# The System screen's role-conflict query at seven years of sign-ins

The System screen (`GET /admin/system`) lists the people whose latest sign-in recorded a `ROLE_CONFLICT` (WP-25b). The
list is read from the audit trail with one query (`StandingEventQueries.standing`): a `ROLE_CONFLICT` event stands if
the person has no later `LOGIN` or `ROLE_CONFLICT` event. Issue 141 asked how that behaves when the trail is years
long. The bar is the project's page target from `cases-list-50k.md`, a p95 under 500 ms (BUILD_PLAN defines no
separate budget for this admin-only screen).

## The seed

`AbstractRoleConflictPerfIT` generates it with SQL, once per vendor. It is synthetic and deterministic and is never
applied by Flyway or packaged.

| What | How many | Shape |
|---|---:|---|
| People | 1 000 | `Perf User 0` … `999`, plus one System Admin |
| Sign-ins | 5 110 000 | `USER` / `LOGIN`: 2 a day for 2 555 days (seven years), per person |
| Case events | 1 000 000 | `CASE` / `CASE_UPDATED`, as bulk the entity index must step over |
| Role conflicts | 2 300 | 40 people over their last 30 days, one per sign-in; 10 of them signed in cleanly afterwards, so **30 conflicts stand** |

Together 6 112 300 `AUDIT_EVENT` rows. Seeding takes about 35 s on PostgreSQL and about 35 min on Oracle (three
indexes are maintained as it goes).

## What was wrong

The query is a `NOT EXISTS`. Its outer side is cheap, but for each of the 2 300 conflict rows the inner lookup went to
`IX_AUDIT_ENTITY (ENTITY_TYPE, ENTITY_ID, AT_TS)` for that person and had to find a later event. The predicate was
`l.at > e.at OR (l.at = e.at AND l.id > e.id)`: an `OR`, so `AT_TS` was only a filter on the index entries, and the
lookup read all of a person's 5 110 sign-ins, from the oldest, to find the one that is later. PostgreSQL showed
`Rows Removed by Filter: 5109` on each of the 2 300 index scans; Oracle's plan had the same `INDEX RANGE SCAN` on
`ENTITY_TYPE, ENTITY_ID` alone.

The issue proposed an index led by `ACTION`. Measured, it was not enough (below): it replaces the outer scan but not
the inner lookups, which are where the time goes.

## The change

The same predicate with `AT_TS` as a range, which `IX_AUDIT_ENTITY` can use as an access condition:

    l.at >= e.at AND (l.at > e.at OR l.id > e.id)

It selects the same rows because `AT_TS` is `NOT NULL` (V2): "later time, or the same time and a higher id" is "not
earlier, and (later, or a higher id)". `AbstractStandingEventEquivalenceIT` runs both forms as SQL over the tie cases
(the same time with the clearing event's id above and below, for a sign-in and for a second conflict, and a later or
earlier time against an id that points the other way) on both vendors, and the application's own query must agree.
No migration and no index.

## The numbers

`GET /admin/system` through MockMvc as a System Admin, 3 warm-up and 20 measured requests, p95. (The page with the
old query was measured the same way in the first runs of this work; "query alone" is the SQL run over JDBC.)

| | PostgreSQL 16 | Oracle Free 23 |
|---|---:|---:|
| old query, `GET /admin/system` | 855 ms | 2 323 ms |
| old query, alone | 836 ms | 2 442 ms |
| old query plus an `ACTION`-led index, page | 754 ms | 1 812 ms |
| **new query, page** | **238 ms** | **261 ms** |
| new query, alone | 205 ms | 181 ms |
| new query plus the `ACTION`-led index, page | 58 ms | 36 ms |

The new query is under the 500 ms bar on both, so no index is added. What remains is the outer side: a full scan of
the audit table for the 2 300 conflict rows (PostgreSQL: a parallel sequential scan, about 165 ms; Oracle: a full
table scan). An `ACTION`-led index (`ACTION, ENTITY_TYPE, ENTITY_ID, AT_TS`) would remove it, to 58 ms and 36 ms,
but costs write time and space on the busiest table in the application for a screen only administrators open; it can
be added as a migration if the trail outgrows the margin.

## The plans

PostgreSQL, new query (`EXPLAIN (ANALYZE, BUFFERS)`, abridged): `Parallel Seq Scan on audit_event e` (actual 167 ms,
767 rows per worker), then `Index Scan using ix_audit_entity on audit_event l (actual time=0.004, rows=1 loops=2300)`
with `Index Cond: ((entity_type = 'USER') AND (entity_id = e.entity_id) AND (at_ts >= e.at_ts))`; execution time
190 ms. The old plan's inner scan was `actual time=0.723 loops=2300` with `Rows Removed by Filter: 5109`.

Oracle, new query (`EXPLAIN PLAN` and `DBMS_XPLAN.DISPLAY`, which need no privilege on the `V$` views):

    |*  2 |   FILTER                              |                 |
    |*  3 |    TABLE ACCESS FULL                  | AUDIT_EVENT     |
    |*  4 |    TABLE ACCESS BY INDEX ROWID BATCHED| AUDIT_EVENT     |
    |*  5 |     INDEX RANGE SCAN                  | IX_AUDIT_ENTITY |
    5 - access("L"."ENTITY_TYPE"='USER' AND "L"."ENTITY_ID"=:B1 AND SYS_EXTRACT_UTC("AT_TS")>=SYS_EXTRACT_UTC(:B2)
              AND SYS_EXTRACT_UTC("AT_TS") IS NOT NULL)

The old plan's step 5 had `access("L"."ENTITY_TYPE"='USER' AND "L"."ENTITY_ID"=:B1)` only, with the time comparison
in the filter of step 4 (estimated 60 rows per lookup against 1 now). The harness writes the full plans of both
queries, and of the indexed variants, to `target/perf/role-conflict-<vendor>.md`.

## How to run it

It is opt-in and is not in any workflow: the Oracle seed alone takes about 35 minutes. The ITs carry their own tag,
`perf-role-conflict`, which the normal build excludes, and the `perf-it` profile has an execution for them:

    ./mvnw -Pperf-it test-compile \
        org.apache.maven.plugins:maven-failsafe-plugin:integration-test@role-conflict \
        org.apache.maven.plugins:maven-failsafe-plugin:verify@role-conflict \
        -Dit.test=PostgresRoleConflictPerfIT        # or OracleRoleConflictPerfIT

Both need Docker. To measure a candidate index in the same run, pass its DDL (separate statements with `;;`):

    -Dperf.index.ddl="CREATE INDEX IX_AUDIT_ACTION ON AUDIT_EVENT (ACTION, ENTITY_TYPE, ENTITY_ID, AT_TS)"

The report then repeats the timings and the plans after the index. Oracle runs on the image of `OracleTestcontainersConfiguration` (Free 23 unless `-Dtrailkeep.oracle.image` names
another). The timings are from a developer machine; compare
the ratios, not the absolute milliseconds.
