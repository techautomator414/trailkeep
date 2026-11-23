<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0017: Serve search from vendor indexes in 1.0.0: Oracle Text on Oracle, a trigram index on PostgreSQL

- **Status:** Accepted (2026-11-23)
- **Date:** 2026-11-23
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #7 (search; amends ADR-0006), A.1 #1 (the Oracle test images), Part C
  "v1.1 backlog" (vendor search adapters)
- **Plan sections:** BUILD_PLAN.md §8 rule 4 ("Search"), §12 ("Performance"), §8 rule 1 (the Oracle matrix); ADR-0006,
  ADR-0003; issue #299

## Context and problem statement

ADR-0006 put search behind `SearchGateway` with a portable `LIKE` adapter and left the vendor adapters to v1.1.
BUILD_PLAN §12 set the condition for pulling them forward: "`LIKE` search p95 < 1 s at 50k cases (if the 500k-comment
seed pushes it past that, the v1.1 vendor adapters are pulled forward)". WP-30 measured it
(`docs/perf/release-500k.md`): on PostgreSQL the searches stay at 375–496 ms p95; on Oracle they take **2.0–2.4 s**,
because `LIKE` reads every case's `CLOB` as a LOB (about a second for the 50 000 rows) and a search runs that scan
twice, for the count and the page. So the adapters move into 1.0.0, and the question is how to build them without
changing what a search finds.

What a search means is fixed by the `LIKE` adapter: every whitespace-separated word of the text must occur in the
case's search text **as a substring**, ignoring case, with `%`, `_` and `\` matching themselves; or the case number
contains the text. Oracle Text indexes **tokens** (runs of letters and digits), not substrings.

## Decision drivers

- Identical results to the `LIKE` adapter, for every reader, restricted cases and team scoping included.
- Nothing a person types may be read as Oracle Text query syntax.
- Index maintenance atomic with the case write (BUILD_PLAN §8 rule 4), or a lag that is decided and tested.
- No new database privileges if they can be avoided; the schema owner holds `CREATE SESSION`, `CREATE TABLE`,
  `CREATE SEQUENCE` and quota (deploy/README.md).
- The migration safe on a 0.3.0 database that already has data, and re-runnable after a failure.

## Considered options

1. **Oracle Text `CONTEXT` index as a filter in front of `LIKE`**, with each word's longest run of letters and digits
   looked up as `%run%`; a `pg_trgm` GIN index on PostgreSQL under the unchanged `LIKE` predicate.
2. Oracle Text with **word or prefix** matching (`CONTAINS(…, 'word')` or `'word%'`) instead of substrings.
3. Oracle Text with a **substring index** (`BASIC_WORDLIST` `SUBSTRING_INDEX TRUE`) and a raised `WILDCARD_MAXTERMS`.
4. Keep `LIKE` on Oracle and halve it: one scan shared by the count and the page.
5. `CTXCAT` (`CATSEARCH`), or Oracle 23ai's `CREATE SEARCH INDEX`.

## Decision outcome

**Option 1.**

**Oracle.** `oracle/V21__search_index.sql` creates `IX_CASE_SEARCH_TEXT`, an Oracle Text `CONTEXT` index on
`CASE_SEARCH.SEARCH_TEXT`, with `DIRECT_DATASTORE`, `NULL_FILTER`, `BASIC_LEXER`, `BASIC_WORDLIST`, `EMPTY_STOPLIST`,
`SYNC (ON COMMIT)`, `TRANSACTIONAL` and `OPTIMIZE (AUTO_DAILY)`, built `ONLINE` (without it every write to
`CASE_SEARCH` fails with ORA-29861 while it builds, and the previous release is still serving).
`OracleTextSearchGateway` puts `CONTAINS(SEARCH_TEXT, ?) > 0` in front of the `LIKE` adapter's match;
`OracleTextQuery` builds the `CONTAINS` text:

- A run of ASCII letters and digits inside a word lies inside one token wherever the word occurs in the text, so
  `%run%` (some token contains the run) is true of every case whose text contains the word. The index can only narrow;
  `LIKE` decides. A word that *is* such a run (`kestrel`, `3fa9c1`) is decided by the index alone; any other word
  (`kestrel-escalation`, `50%`, `straße`) is still matched by `LIKE` on the cases the index found.
- Each word contributes its longest run; runs shorter than 3 are not looked up (`%ab%` expands to tens of thousands of
  tokens and took 3 s at the 500k seed); at most 2 runs, the longest, are looked up per search, the other words go to
  `LIKE`; a run over 64 characters is cut and its word goes to `LIKE`. If no word has a usable run, the search is the
  `LIKE` adapter's. The cap is the cost: a leading wildcard makes Oracle Text read every token of the index to expand
  `%run%`, about 90 ms per run at the 500k seed (600 000 distinct tokens), in each statement of a search.
- **No query injection by construction:** the `CONTAINS` text is only `%run%` terms of `[a-z0-9]` joined by `AND`, bound
  as a parameter. No operator, reserved word (`ABOUT`, `NEAR`, `AND`, `WITHIN`, …) or special character (`{ } - & | $
  ? ! = ( ) [ ] ; , ~ > * \ ' "`) typed into a search reaches Oracle Text; reserved words inside `%…%` are terms.
- **Too many tokens:** Oracle Text refuses a wildcard that expands past the word list's limit (DRG-51030; 20 000 with
  the system word list). An error from a JPA query would mark the transaction for rollback, so `OracleTextJdbcDao`
  first asks the index with the same `CONTAINS` text over JDBC (`ROWNUM <= 1`), where Oracle rolls back only that
  statement; if it is refused the search is the `LIKE` adapter's. The answer is kept for the rest of the transaction,
  so the list's count and page ask once: one more lookup per search.
- **A join, not a subquery under `OR`.** The `LIKE` adapter's `CASE_RECORD.ID IN (subquery) OR <case number>`
  is not unnested by Oracle 21 when the subquery holds `CONTAINS`: it becomes a filter that evaluates `CONTAINS`
  case by case without the index (1.25 s at the 500k seed on XE 21; Free 23 unnests it). The adapter left-joins
  `CASE_SEARCH`, one row a case, so no row repeats and counts stay exact, with `CONTAINS` (and the remaining `LIKE`
  words) in the join's `ON` and `CASE_SEARCH.CASE_ID IS NOT NULL OR <case number>` in the `WHERE`; both images plan it
  as a hash join on the domain index. The `LIKE` adapter keeps its subquery, which PostgreSQL hashes once.
- **The query is bound** (Hibernate's `value()`, not `literal()`, which would put every search's text into a new
  statement and hard-parse it).
- **No optimizer statistics on the index.** When a `CONTEXT` index has statistics, Oracle Text estimates a `CONTAINS`
  predicate's selectivity by running the query, and the optimizer asks for that estimate at every execution of a
  statement with a bound query: measured, it doubled every search (a constant-bind loop went from 90 to 180 ms a
  statement once `DBMS_STATS` had gathered the table with `cascade`). Without statistics the estimate is immediate. V21
  therefore deletes the index's statistics and locks those of `CASE_SEARCH` as they stand, which the automatic
  statistics job respects; the table is read by its primary key, this index or a full scan, so frozen statistics cost
  nothing measurable (and with none at all, dynamic sampling serves).
- **Atomicity:** `SYNC (ON COMMIT)` indexes a transaction's rows before its commit returns, and `TRANSACTIONAL` makes a
  query read rows not yet indexed, including the session's own uncommitted ones. A search therefore always sees
  exactly the committed text plus its own transaction's, as `LIKE` does: **no lag**. The cost is index fragments, one
  set per commit; `OPTIMIZE (AUTO_DAILY)` has `CTXSYS`'s scheduler optimise the index nightly.
- **Privileges:** every preference is a `CTXSYS` system preference, so the schema owner needs no `CTXAPP` role and no
  `EXECUTE ON CTX_DDL` (verified with an owner holding only the documented privileges, on XE 21 and Free 23). The
  runtime, purge and import users need nothing new. `BASIC_LEXER` and `BASIC_WORDLIST` are pinned rather than the
  `DEFAULT_*` preferences, which follow the database language (for some languages they add compound splitting or
  alternate spellings).
- **The one known difference from `LIKE`:** Oracle Text keeps only a token's first 255 bytes, so a word that occurs
  only past byte 255 of an unbroken run of letters and digits (a pasted encoded blob, for example) is not found.
  `OracleSearchParityIT` asserts it, so it stays known.
- **Prerequisite:** Oracle Text is installed. It is part of every Oracle Database edition and installed by default
  (`DBA_REGISTRY` component `CONTEXT`); the `-slim` container images remove it. The CI matrix therefore runs
  `gvenzl/oracle-xe:21` and `gvenzl/oracle-free:23`, the full images, instead of their `-slim` variants
  (supersedes the image names in IMPLEMENTATION_PLAN A.1 #1, ADR-0003 and BUILD_PLAN §8 rule 1; the versions and the
  dialect pin are unchanged), and `compose.yaml` follows.

**PostgreSQL.** `postgresql/V21__search_index.sql` creates `pg_trgm` if absent and a GIN index
`IX_CASE_SEARCH_TEXT (SEARCH_TEXT gin_trgm_ops)`. The `LIKE` adapter is unchanged: PostgreSQL answers its patterns
from the index and rechecks every candidate, so the results are `LIKE`'s by construction. PostgreSQL was within
target without it (496 ms p95 under load, 425 ms in process); the index is added for parity of design and headroom,
not to meet the target. `pg_trgm` is a trusted extension (PostgreSQL 13+): the owner creates it if it may create in
the database, or an administrator creates it once beforehand.

**Selection.** `app.search.adapter` names the adapter, and each vendor profile sets it: `oracle-text` on Oracle,
`like` on PostgreSQL. `like` is valid on both (on Oracle it scans, as before, while the index is still maintained);
`oracle-text` on PostgreSQL fails start-up. Within `oracle-text`, the `LIKE` adapter remains the fallback described
above.

### Consequences

- **Good:** the Oracle search no longer reads every `CLOB`; the results are the `LIKE` adapter's, by an argument
  that the parity test checks for every reader; no new privilege on either vendor; no indexing lag.
- **Bad:** Oracle Text becomes a prerequisite on Oracle, and the Oracle CI images grow (about 1.2 to 1.5 GB compressed
  against 0.75 to 0.85 GB). A search costs one extra index lookup for the DRG-51030 probe, and each looked-up run
  costs a read of every token, so search time grows with the vocabulary (Option 3 is the remedy). A search for words
  that occur in nearly every case is still a scan in effect (the index returns almost every row and `LIKE` reads
  them). The 255-byte token limit is a known difference. `CASE_SEARCH`'s optimizer statistics are locked: a DBA who
  unlocks them or gathers them with `force` makes every search slower until the index's statistics are deleted again.
  Every commit that touches `CASE_SEARCH` adds index fragments until the nightly optimisation, which needs
  `DBMS_SCHEDULER` to run jobs (`JOB_QUEUE_PROCESSES` > 0, the default).

### Rejected options

- **Option 2** changes what a search finds (`udit` no longer finds `audit`; a reference fragment no longer finds the
  reference), which users of the `LIKE` search rely on.
- **Option 3** makes the expansion cheap (about 10 ms instead of 90 ms per run, measured on XE 21 at the 500k seed),
  but needs `CTX_DDL` (the `CTXAPP` role) for a custom word list, its substring table was 296 MB beside the 80 MB
  token table (the index 3.7 times larger), and building it took 7 min 20 s against 33 s to 95 s without it. Option 1
  meets the target without it; it is the lever if vocabularies grow well past the 500k seed's (one migration and a
  grant).
- **Option 4** leaves about 1.2 s, still over the target (`docs/perf/release-500k.md`).
- **Option 5:** `CTXCAT` has no leading wildcard, so no substring matching; `CREATE SEARCH INDEX` covers text columns
  from 23ai only, not 19c or 21c.

## Confirmation

The PostgreSQL index and this record land first (#299, part 1); the Oracle adapter and its tests land with the
pull request that implements them (part 2).

- `OracleSearchParityIT` (`-Poracle-it`, XE 21 and Free 23): committed corpus, about sixty searches (words, parts of
  words, case, punctuation, every Oracle Text operator and reserved word) for five readers (Compliance Admin, team
  manager, owner of a restricted case, a colleague, another team): identical case numbers and counts from both
  adapters; the writer's own uncommitted text found in its transaction; the DRG-51030 fallback; the 255-byte
  difference.
- `OracleSearchIndexIT`: the index is valid, `ON COMMIT`, `AUTO_DAILY`, built from system preferences with no stop
  words; its script runs again without harm; the runtime and purge users write `CASE_SEARCH` with their existing
  grants. `OracleMigrationJobIT` migrates a fresh owner holding only the documented privileges.
- `OracleTextQueryTest`: the `CONTAINS` text is only `%[a-z0-9]{3,64}%` terms joined by `AND`, for inputs made of
  Oracle Text syntax.
- `PostgresSearchIndexIT` (every build): `pg_trgm` and the GIN index exist and serve the `LIKE` pattern.
- `OracleComments500kPerfIT` (`perf-500k-oracle`) and `PostgresComments500kPerfIT` (`perf-500k`, nightly) meet
  the 1 s search target at the 500k seed; `OracleCaseListSearchIT` and `AbstractCaseListSearchIT` keep their
  behaviour through the Oracle Text adapter.

## More information

ADR-0006 (the gateway and `CASE_SEARCH`; its maintenance rules are unchanged), ADR-0003 (the Oracle matrix),
`docs/perf/release-500k.md` (before and after), deploy/README.md ("Search indexes").
