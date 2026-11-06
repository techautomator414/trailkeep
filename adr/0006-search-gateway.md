<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0006: Search behind `SearchGateway` with an atomic `CASE_SEARCH` side table

- **Status:** Accepted (amended 2026-11-05)
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #7 (amended r3, r4); A.5 #44
- **Plan sections:** BUILD_PLAN.md §8 rule 4 and rule 6

## Context and problem statement

Search must work across replicas and on both databases, over case text that is readable by the same principals as the case. A plain `String` bind over 4000 bytes inside an Oracle expression raises ORA-01461, which breaks "append a comment to the search text". Embedded Lucene needs shared storage across replicas.

## Decision drivers

- Concurrent writers must not lose each other's text.
- A colleague's comment must not fail your Save (no shared `@Version`).
- Behavior must be identical on Oracle and PostgreSQL.
- Search results must go through `CaseScope`.

## Considered options

1. Embedded Lucene index.
2. Vendor full-text search from day one (PostgreSQL `pg_trgm`, Oracle Text).
3. A `SearchGateway` interface with a portable `LIKE` adapter in v1 and vendor adapters in v1.1.

## Decision outcome

**Option 3.** `CASE_SEARCH (CASE_ID PK, SEARCH_TEXT ${clob}, UPDATED_AT)` is a 1:1 side table with no `@Version`. It is maintained atomically in the writer's transaction. A comment append is `UPDATE … SET SEARCH_TEXT = SEARCH_TEXT || :fragment` with the fragment **bound as a CLOB / character stream**: a character stream on PostgreSQL, a CLOB on Oracle, whose driver sends a stream over 32 KB as `LONG` and fails the append with ORA-01461 (corrected after #52). A title or description edit rebuilds the text after `SELECT … FOR UPDATE` on the single `CASE_SEARCH` row. Comment bodies are capped at 20 000 characters. **Subject is excluded; description is included**: it is readable by the same principals under the same grants and encryption, and search without it would be useless. The `LIKE` adapter returns candidate ids that `CaseScope` filters. Native SQL stays inside `casefile`'s `*JdbcDao`. Vendor adapters are v1.1 behind the same interface.

### Consequences

- **Good:** no lost updates, no editor conflicts, same behavior on both vendors.
- **Bad:** `LIKE '%term%'` is a scan on both vendors. The stated expectation is "fine at about 50k cases, measured at the 50k seed; not fine at 500k comments". `SEARCH_TEXT` duplicates comment content.

## Amendment (2026-11-05)

WP-15 part 1b (decided on #62). This changes how the `LIKE` adapter meets `CaseScope`; table maintenance is unchanged.

- **Reason.** "The adapter returns candidate ids that `CaseScope` filters" needs a round trip: the ids come back to Java and are bound again as `CASE_RECORD.ID IN (…)`. A common word at 50k cases yields tens of thousands of ids, every list and count would carry them, and Oracle refuses more than 1000 expressions in one `IN` list.
- **Mechanism.** `SearchGateway` contributes a JPA Criteria **predicate** to the caller's query instead of returning ids: `CASE_RECORD.ID IN (SELECT CASE_ID FROM CASE_SEARCH WHERE LOWER(SEARCH_TEXT) LIKE ? ESCAPE '\' AND …)`, one bound pattern per word, or the case number containing the text. It sits in the same `WHERE` as `CaseScope.toPredicate`, so candidate ids never leave the database and counts over a search are exact.
- **No native SQL.** JPA expresses `LOWER(…) LIKE … ESCAPE` on the `CLOB` (Oracle) and `text` (PostgreSQL) column, and Part B allows native SQL only where JPA cannot express the statement. The `CASE_SEARCH` append stays in `CaseSearchJdbcDao`; search needs no `*JdbcDao`.
- **Lower-case at write (2026-11-06, #69).** Hibernate refuses `lower()` on the `CLOB`-mapped column, so `SEARCH_TEXT` is stored lower-cased (`Locale.ROOT`) by every writer and the adapter applies no function to it: its predicate is `SEARCH_TEXT LIKE ? ESCAPE '\'` with lower-cased words, which supersedes the `LOWER(SEARCH_TEXT)` written above; migration V4.5 lower-cases rows written before.
- **Vendor adapters (v1.1)** stay behind the same interface, as predicates: a `pg_trgm` or Oracle Text adapter registers its operator or `CONTAINS` as a Hibernate function and returns it as a predicate.
- **Proof.** `AbstractCaseListSearchIT` runs the adapter on PostgreSQL (every PR) and on Oracle (`OracleCaseListSearchIT`, `-Poracle-it` on `main`).

## Confirmation

- WP-13 acceptance: two concurrent comments both appear in `SEARCH_TEXT`; the **Oracle IT appends a comment longer than 4000 bytes**.
- WP-15 acceptance: p95 < 500 ms at 50k cases on PostgreSQL (k6 smoke); restricted rows absent from results, counts and CSV.
- Semgrep rule and `*JdbcDao` placement rule for native SQL.
