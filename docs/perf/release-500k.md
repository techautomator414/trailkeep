<!-- SPDX-License-Identifier: Apache-2.0 -->
# Performance at the 500k seed (WP-30 part a)

WP-30 (IMPLEMENTATION_PLAN Part C) asks for k6 at the 50k and 500k seeds and for pool sizing. This page records what
"500k" is, how the runs are made, the numbers, and the decisions taken from them: the Hikari pool, the exports'
database connections, and the cases-list indexes that `cases-list-50k.md` left for this run. The restore drill, the
runbook and the evidence pack are later parts of WP-30. The two changes the numbers led to are their own pull
requests: the exports' transactions (#295) and the pool size (#296). The k6 tables below were measured before either.
The Oracle search missed its target, so the vendor search adapters were pulled forward (#299, ADR-0017); "Vendor
search adapters (#299)" records the numbers after them.

## What is measured, and against what

BUILD_PLAN's performance row (§12, "Performance") is the target, and its Appendix B `perf` line says the same:

| What | Target |
|---|---|
| Seed | **50k cases / 500k comments** |
| Cases list | p95 < 500 ms |
| `LIKE` search | p95 < 1 s at 50k cases; "if the 500k-comment seed pushes it past that, the v1.1 vendor adapters are pulled forward" |
| Aging report | < 30 s |

So "500k" is **comments**, not cases and not audit rows: the case count stays at 50 000, and the comments are what
grow. Comments reach three places, and those are what the 500k seed can slow down: each comment is appended to its
case's `CASE_SEARCH` text (so the `LIKE` search scans about seventeen times more text), the case page lists them all,
and each one writes an audit event (the History tab). The list itself reads neither. The case page and the History tab
have no target of their own in the plan; they are held to the list's page target, 500 ms, as `role-conflict.md` does
for its screen.

## The seed

`perf/seed-cases-postgresql.sql` is the 50k seed of `cases-list-50k.md`, unchanged but for one statement that adds the
local stack's dev-realm Analyst to Perf Team 01 (a no-op anywhere else), so k6 can measure a one-team view.
`perf/seed-comments-postgresql.sql` is applied on top of it; together they are **the 500k seed**. Both are synthetic and
deterministic, never applied by Flyway and never packaged. `perf/seed-cases-oracle.sql` and
`perf/seed-comments-oracle.sql` write the same rows, value for value, on Oracle.

| What | How many | Shape |
|---|---:|---|
| Comments | 500 000 | 8 per case, except every hundredth case (500 cases), which has 208: 49 500 × 8 + 500 × 208. Two to four sentences from a fixed set of twenty and a reference token, 173 characters on average. One comment in 997 carries the word "kestrel", which appears nowhere else (501 comments, 482 listed cases) |
| Audit events | 500 000 | one `COMMENT_ADDED` per comment, its body redacted to an HMAC-shaped digest and a length, as the application writes it |
| Search text | 50 000 rows | each case's comments appended lower-cased after a line break, as the application appends them: 1 837 characters a case on average (about 110 with the 50k seed alone), 36 261 for the longest; 89 MB on PostgreSQL |

On PostgreSQL the layer loads in about 27 s (33 s in the IT, with the case seed).

## How it is measured

### In process: `PostgresComments500kPerfIT` (nightly) and `OracleComments500kPerfIT` (by hand)

`AbstractComments500kPerfIT` applies both seeds to its own container and sends each scenario 10 times to warm up and
60 times measured, through MockMvc, with security, the scoped queries and Thymeleaf (`PerfReport`, shared with
`PerfCasesListIT`). It asserts the targets: the list and the case pages under 500 ms, every search under 1 s, the aging
report under 30 s. Posting a comment to the 208-comment case is timed last, as it adds comments.

    # PostgreSQL, also run every night after PerfCasesListIT:
    ./mvnw -Pperf-it test-compile org.apache.maven.plugins:maven-failsafe-plugin:integration-test@perf-500k org.apache.maven.plugins:maven-failsafe-plugin:verify@perf-500k
    # Oracle Free 23 (add -Dtrailkeep.oracle.image=oracle-xe for xe 21), opt-in:
    ./mvnw -Pperf-it test-compile org.apache.maven.plugins:maven-failsafe-plugin:integration-test@perf-500k-oracle org.apache.maven.plugins:maven-failsafe-plugin:verify@perf-500k-oracle

The tables go to `target/perf/comments-500k-postgresql.md` and `target/perf/comments-500k-oracle.md`.

### Over HTTP: `perf/k6/release-mix.js`

Every read a person makes, against a running application signed in through the identity provider. The mix is fixed,
not random, so two runs send the same requests in the same proportions:

| Scenario | Virtual users | Each iteration, back to back |
|---|---:|---|
| `browse` (Compliance Admin) | `VUS`, 10 by default | the list newest first, by title, by due date, the last page, filtered; three searches (two title words, the comment-only word "kestrel", a case-number prefix); two case pages; a 208-comment case page and its History tab; the dashboard |
| `analyst` (one team) | 2 | the list, a search, a case of the team |
| `reports` (Compliance Admin) | 1 | the aging report, its CSV, the SLA compliance report; 5 s pause |
| `exports` (Compliance Admin) | 1 | the 9 645-row export with details; 5 s pause |

No virtual user pauses in `browse` or `analyst`: ten of them keep about ten requests in flight all the time, which is
a load far above what the plan's users make, and is chosen to find the limits rather than to model a day. The
thresholds are the targets above, and every check must pass. To run it on the local stack:

    docker compose up -d postgres keycloak clamav-tls-init
    ./mvnw -DskipTests package
    java -Xms1g -Xmx2g -jar target/trailkeep-*.jar --spring.profiles.active=local \
        --app.attachments.scanning=external --app.rate-limit.enabled=false
    # once the application has migrated the schema and seeded its dev-realm users:
    psql -h localhost -U trailkeep -d trailkeep -f perf/seed-cases-postgresql.sql      # the 50k seed
    psql -h localhost -U trailkeep -d trailkeep -f perf/seed-comments-postgresql.sql   # + this: the 500k seed
    docker run --rm --network host -e PASSWORD -e VUS=10 -e DURATION=3m -v "$PWD/perf/k6:/scripts:ro" \
        grafana/k6:1.4.2 run --summary-trend-stats "med,p(95),p(99),max,count" /scripts/release-mix.js

`PASSWORD` is the dev realm's (`deploy/local/keycloak/README.md`). The rate limit is off because every virtual user of
a scenario is the same person, whose search bucket (120 a minute) a load test empties in seconds; `prod` refuses that
setting. `setup()` signs each user in once before the scenarios start: the first sign-in of a person creates their row,
and twelve virtual users doing that at once is a race this run does not measure (see "Found on the way").
`perf/k6/cases-smoke.js`, WP-15's smoke, still works the same way; both scripts now copy the identity provider's
cookies too, which Keycloak 26 marks `Secure` like the application's.

### Hardware and software

One desktop machine, everything on it: a 16-core AMD Ryzen 9 5900XT, 62 GB RAM, NVMe storage; PostgreSQL 16
(`postgres:16-alpine` from `compose.yaml`, default settings) in Docker, the application on Temurin 21 with
`-Xms1g -Xmx2g` on the host, k6 1.4.2 in Docker on the host network. The machine runs other services, so numbers move
by some percent between runs; what is compared below was measured back to back. The nightly CI runner measured
`PerfCasesListIT` within a few percent of this machine (run 45024201625: `GET /cases` p95 122.5 ms against 132.1 ms here).

## Results

### k6 at the 50k and the 500k seed (PostgreSQL, 10 browse users, 3 minutes, Hikari pool 10)

Times in ms. `n` is how many requests of that kind the run made.

| Request | 50k: n | p50 | p95 | p99 | max | 500k: n | p50 | p95 | p99 | max | Target |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|
| `GET /cases` | 1 039 | 171 | **215** | 257 | 379 | 713 | 142 | **190** | 205 | 247 | p95 < 500 |
| `GET /cases?sort=title` | 1 039 | 210 | 256 | 316 | 419 | 713 | 174 | 226 | 248 | 290 | p95 < 500 |
| `GET /cases?sort=due` | 1 039 | 174 | 222 | 264 | 400 | 713 | 140 | 189 | 208 | 278 | p95 < 500 |
| `GET /cases?page=1900` | 1 039 | 222 | 269 | 313 | 363 | 713 | 183 | 231 | 272 | 335 | p95 < 500 |
| `GET /cases` filtered | 1 039 | 173 | 216 | 233 | 274 | 713 | 134 | 186 | 200 | 271 | p95 < 500 |
| `GET /cases?q=plover audit` | 1 039 | 191 | **235** | 255 | 276 | 713 | 428 | **496** | 541 | 610 | p95 < 1 000 |
| `GET /cases?q=kestrel` | 1 039 | 111 | 129 | 140 | 165 | 713 | 398 | **460** | 496 | 546 | p95 < 1 000 |
| `GET /cases?q=PERF-2026-0…` | 1 039 | 183 | 226 | 244 | 268 | 713 | 428 | 492 | 531 | 569 | p95 < 1 000 |
| `GET /cases/{n}` | 2 078 | 61 | 87 | 104 | 143 | 1 426 | 56 | 94 | 181 | 354 | p95 < 500 |
| `GET /cases/{n}`, 208 comments | 1 039 | 65 | 89 | 110 | 142 | 713 | 237 | **390** | 432 | 469 | p95 < 500 |
| `GET /cases/{n}/history`, 208 comments | 1 039 | 22 | 34 | 42 | 70 | 713 | 22 | 43 | 84 | 171 | p95 < 500 |
| `GET /` (dashboard) | 1 039 | 79 | 95 | 111 | 134 | 713 | 71 | 110 | 147 | 221 | p95 < 500 |
| Analyst: `GET /cases` | 1 464 | 114 | 155 | 176 | 253 | 681 | 86 | 135 | 158 | 231 | p95 < 500 |
| Analyst: `GET /cases?q=plover audit` | 1 464 | 71 | 90 | 104 | 234 | 681 | 342 | 387 | 410 | 471 | p95 < 1 000 |
| Analyst: `GET /cases/{n}` | 1 464 | 56 | 78 | 108 | 553 | 681 | 56 | 264 | 327 | 407 | p95 < 500 |
| `GET /reports/aging` | 35 | 90 | 114 | 230 | **287** | 35 | 81 | 132 | 154 | **164** | < 30 000 |
| `GET /reports/aging.csv` | 35 | 70 | 80 | 153 | 189 | 35 | 65 | 84 | 105 | 116 | < 30 000 |
| `GET /reports/compliance` | 35 | 70 | 83 | 181 | 231 | 35 | 64 | 86 | 112 | 124 | < 30 000 |
| `GET /cases/export.csv`, 9 645 rows | 32 | 645 | 725 | 1 078 | 1 225 | 33 | 569 | 655 | 676 | 684 | none |
| **All requests** | 18 131 | | | | | 11 545 | | | | | |
| **Throughput** | 97.8/s | | | | | 62.3/s | | | | | |
| **Errors** | 0 | | | | | 0 | | | | | |

The Analyst's case at the 500k seed is one of the 208-comment cases one time in five, hence its p95.

### In process at the 500k seed (`PostgresComments500kPerfIT`, 60 measured requests each, ms)

| Scenario | p50 | p95 | max | Target |
|---|---:|---:|---:|---|
| `GET /cases`, Compliance Admin, newest first | 118.7 | 134.6 | 143.9 | p95 < 500 |
| `GET /cases?q=plover audit` | 397.0 | **425.1** | 438.8 | p95 < 1 000 |
| `GET /cases?q=kestrel` | 357.9 | 374.9 | 382.4 | p95 < 1 000 |
| `GET /cases?q=PERF-2026-0499` | 384.5 | 409.5 | 417.6 | p95 < 1 000 |
| `GET /cases?q=plover audit`, Analyst | 329.4 | 349.0 | 372.7 | p95 < 1 000 |
| `GET /cases/{n}`, 8 comments | 43.6 | 56.0 | 75.0 | p95 < 500 |
| `GET /cases/{n}`, 208 comments | 98.4 | 108.4 | 114.4 | p95 < 500 |
| `GET /cases/{n}/history`, 208 comments | 13.5 | 18.0 | 35.9 | p95 < 500 |
| `GET /reports/aging` | 62.1 | 69.9 | 75.2 | < 30 000 |
| `POST /cases/{n}/comments`, 208 comments | 6.4 | 9.9 | 12.4 | none |

The search's own statement, `EXPLAIN ANALYZE` of the comment-word subquery: a sequential scan of `CASE_SEARCH` that
removes 49 499 rows, 131 ms. A search runs it twice (the count and the page), which is most of the 375 to 425 ms.

### In process on Oracle Free 23 (`OracleComments500kPerfIT`, 60 measured requests each, ms)

The same IT, on the `gvenzl/oracle-free` image the `oracle-it` profile uses, at the 50k seed (the comment seed left out
for this one run) and at the 500k seed. Seeding the 500k layer takes about 5.8 minutes on Oracle.

| Scenario | 50k: p50 | p95 | max | 500k: p50 | p95 | max | Target |
|---|---:|---:|---:|---:|---:|---:|---|
| `GET /cases`, Compliance Admin, newest first | 104.6 | 123.2 | 138.1 | 108.9 | 127.3 | 130.9 | p95 < 500 |
| `GET /cases?q=plover audit` | 314.1 | 331.6 | 333.4 | 2 014.4 | **2 398.1** | 2 487.7 | p95 < 1 000 |
| `GET /cases?q=kestrel` | 273.2 | 290.7 | 293.7 | 1 921.2 | **2 043.1** | 85 944.8 | p95 < 1 000 |
| `GET /cases?q=PERF-2026-0499` | 295.3 | 321.1 | 329.4 | 1 943.1 | **2 169.8** | 2 236.6 | p95 < 1 000 |
| `GET /cases?q=plover audit`, Analyst | 244.2 | 253.9 | 257.8 | 539.7 | 574.3 | 581.5 | p95 < 1 000 |
| `GET /cases/{n}`, 8 comments | 44.5 | 53.7 | 72.9 | 48.2 | 58.1 | 94.2 | p95 < 500 |
| `GET /cases/{n}`, 208 comments | 38.6 | 46.7 | 54.0 | 109.6 | 126.1 | 142.9 | p95 < 500 |
| `GET /cases/{n}/history`, 208 comments | 11.8 | 15.8 | 19.1 | 18.5 | 21.8 | 26.1 | p95 < 500 |
| `GET /reports/aging` | 74.3 | 85.6 | 92.1 | 72.4 | 85.3 | 92.5 | < 30 000 |
| `POST /cases/{n}/comments` | 14.6 | 18.0 | 69.1 | 17.7 | 23.0 | 36.3 | none |

(At 50k the "208 comments" case has none.) One `kestrel` request of the sixty took 86 s in the run that recorded this
table. An earlier run of the same IT, which stopped at its first miss, measured the title-word search at a 2.1 s p95
with a 2.2 s maximum, so the outlier is reported, not explained. The plan of the search's statement is a full scan of `CASE_SEARCH` with the
filter `"SEARCH_TEXT" /*+ LOB_BY_VALUE */ LIKE '%kestrel%'`: each row's `CLOB` is read and matched as a LOB, about a
second for the 50 000 rows, and a search runs it twice. Only the 500 longest texts (over 1 982 characters) exceed what
Oracle keeps in the row, so storage out of line is not the cause; matching a `CLOB` is simply that much slower than
matching PostgreSQL's `text`.

## Vendor search adapters (#299)

BUILD_PLAN §12 decided in advance what the Oracle numbers above trigger: the vendor search adapters move from v1.1
into 1.0.0. ADR-0017 records the design; in short, V21 adds an Oracle Text `CONTEXT` index on `CASE_SEARCH.SEARCH_TEXT`
and `OracleTextSearchGateway` puts `CONTAINS(SEARCH_TEXT, '%run%') > 0` in front of the `LIKE` predicate (each word's
longest run of letters and digits, at most two runs a search, `LIKE` still deciding every other word), so the results
stay exactly `LIKE`'s; on PostgreSQL V21 adds a `pg_trgm` GIN index under the unchanged `LIKE` predicate (part 1).

### In process at the 500k seed, before and after (60 measured requests each, ms)

Before is the Free 23 table above (the `LIKE` adapter); after is the same IT on the same images, with V21.

| Scenario | Free 23 before: p95 | Free 23 after: p50 | p95 | max | XE 21 after: p50 | p95 | max | Target |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `GET /cases`, Compliance Admin, newest first | 127.3 | 89.9 | 103.1 | 120.3 | 96.8 | 120.3 | 122.5 | p95 < 500 |
| `GET /cases?q=plover audit` | 2 398.1 | 710.9 | **724.0** | 742.3 | 738.9 | **755.1** | 776.7 | p95 < 1 000 |
| `GET /cases?q=kestrel` | 2 043.1 | 394.7 | **399.7** | 406.6 | 415.4 | **423.0** | 426.7 | p95 < 1 000 |
| `GET /cases?q=PERF-2026-0499` | 2 169.8 | 388.5 | **398.4** | 405.6 | 405.1 | **418.4** | 424.8 | p95 < 1 000 |
| `GET /cases?q=plover audit`, Analyst | 574.3 | 647.1 | 652.0 | 660.9 | 241.5 | 247.3 | 250.6 | p95 < 1 000 |
| `GET /cases/{n}`, 8 comments | 58.1 | 40.5 | 51.0 | 59.6 | 43.0 | 51.7 | 84.9 | p95 < 500 |
| `GET /cases/{n}`, 208 comments | 126.1 | 90.1 | 104.9 | 118.4 | 92.1 | 108.3 | 129.4 | p95 < 500 |
| `GET /cases/{n}/history`, 208 comments | 21.8 | 15.1 | 18.9 | 21.7 | 16.6 | 22.9 | 33.7 | p95 < 500 |
| `GET /reports/aging` | 85.3 | 62.1 | 68.9 | 72.7 | 64.1 | 69.8 | 78.1 | < 30 000 |
| `POST /cases/{n}/comments`, 208 comments | 23.0 | 92.6 | 107.0 | 35 927.2 | 35.5 | 52.8 | 45 833.3 | none |

On PostgreSQL, `PostgresComments500kPerfIT` before (the table above, `LIKE` scanning `CASE_SEARCH`) and after (the same
patterns answered from the trigram index):

| Scenario | Before: p95 | After: p50 | p95 | max | Target |
|---|---:|---:|---:|---:|---|
| `GET /cases`, Compliance Admin, newest first | 134.6 | 96.7 | 108.8 | 116.1 | p95 < 500 |
| `GET /cases?q=plover audit` | 425.1 | 98.1 | **104.4** | 107.1 | p95 < 1 000 |
| `GET /cases?q=kestrel` | 374.9 | 101.2 | **106.3** | 108.6 | p95 < 1 000 |
| `GET /cases?q=PERF-2026-0499` | 409.5 | 80.6 | **90.7** | 102.2 | p95 < 1 000 |
| `GET /cases?q=plover audit`, Analyst | 349.0 | 40.6 | 43.6 | 56.0 | p95 < 1 000 |
| `GET /cases/{n}`, 208 comments | 108.4 | 80.5 | 93.3 | 95.5 | p95 < 500 |
| `POST /cases/{n}/comments`, 208 comments | 9.9 | 8.9 | 14.9 | 18.0 | none |

The comment-word subquery is now a bitmap scan of the trigram index (501 rows, 14 ms) where it was a 131 ms scan, and
the planner hashes it once for the statement. Seeding the 500k layer takes 55 s instead of 33 s, the index being
maintained as the comments are appended.

**Every search target is now met on both Oracle images and on PostgreSQL**, at a third of the time or less, and
`OracleComments500kPerfIT` passes on both images. What the times are made of, measured on Free 23 with the same seed:

- **Expanding `%run%` reads every token of the index**, about 90 ms per run (the seed has 600 000 distinct tokens,
  half a million of them the comments' reference codes), and each of a search's statements expands it again: a
  one-word search is three expansions (the DRG-51030 probe, the count, the page), a two-word search six. A word
  without the leading wildcard (`kestrel`, `kestrel%`) costs under 1 ms, but would not find `estre`. Oracle's
  substring index makes the expansion about 10 ms; ADR-0017 records why it is not used yet and when it should be.
- **Oracle 21 needs the join.** With the `LIKE` adapter's shape, `CASE_RECORD.ID IN (subquery) OR <case number>`,
  XE 21 does not unnest the subquery and evaluates `CONTAINS` case by case without the index: 1.2 to 1.7 s, where
  Free 23 unnests it. The adapter left-joins `CASE_SEARCH` instead, which both plan as a hash join on the domain index
  (the plan is in the IT's report).
- **Optimizer statistics on the index doubled every search.** With them, Oracle Text estimates a `CONTAINS`
  predicate's selectivity by running the query, at every execution of a statement with a bound query: a loop of one
  bound statement went from 90 ms to 180 ms an execution once `DBMS_STATS` had gathered the table with `cascade`,
  which the Oracle seeds did and the nightly statistics job would. V21 deletes the index's statistics and locks
  `CASE_SEARCH`'s; the seeds no longer gather that table. The first runs of this IT, before that and before the query
  was bound and the probe asked once per transaction, measured 1.5 to 2.1 s.
- **Posting a comment costs more** on Oracle (23 ms to 107 ms p95 on Free 23, 53 ms on XE 21, for the 208-comment
  case): the commit re-indexes the case's whole search text, about 36 000 characters, before it returns
  (`SYNC (ON COMMIT)`), and writes about 240 KB of redo doing so. **With the image's 20 MB redo logs one post of the
  sixty stalls for 55 to 62 s; with three 512 MB groups none does** (issue 308, below). The IT therefore resizes its
  container's redo to three 512 MB groups before it starts (`-Dtrailkeep.oracle.redo-mb=0` measures the image as
  shipped), so its max is the cost of a post and not of a small test container.

#### The comment-post stall (issue 308): redo size, measured

`-Dtrailkeep.perf.oracle.diag=true` makes the IT snapshot `V$LOG`, `V$LOGFILE`, `V$LOG_HISTORY`, `V$SYSTEM_EVENT` and
`V$SYSSTAT` around the comment-post scenario (as `SYSTEM` on the container's root) and sample every waiting session
twice a second, blockers included. Comment post, 60 requests (p50 / p95 / max, ms):

| | redo | p50 | p95 | max |
|---|---|---:|---:|---:|
| Free 23 | 2 x 20 MB (image), run 1 | 93.3 | 111.5 | **61 338** |
| Free 23 | 2 x 20 MB (image), run 2 | 90.3 | 97.7 | **61 703** |
| Free 23 | 3 x 512 MB, run 1 | 107.1 | 123.6 | 143.5 |
| Free 23 | 3 x 512 MB, run 2 (the IT's default) | 93.4 | 100.3 | 106.9 |
| XE 21 | 2 x 20 MB (image) | 35.0 | 54.5 | **54 931** |
| XE 21 | 3 x 512 MB (the IT's default) | 38.8 | 48.0 | 332.2 |

Wait events in the comment-post window (Free 23: run 2 with 20 MB, run 1 with 512 MB; XE 21: the two runs):

| | Free 23, 20 MB | Free 23, 512 MB | XE 21, 20 MB | XE 21, 512 MB |
|---|---:|---:|---:|---:|
| `log file switch (checkpoint incomplete)` | 1 wait, 0.06 s | none | 9 waits, **137.9 s** | none |
| `enq: CR - block range reuse ckpt` | 12 waits, **61.6 s** | 12 waits, 0.1 s | none | none |
| `db file async I/O submit` (DBW0) | 28 waits, **62.1 s** | 15 waits, 0.14 s | 3 s seen waiting | none seen |
| `redo log space requests` | +2 | +0 | **+1431** | +0 |
| log switches in the window | 1 | 0 | 1 | 0 |

- **XE 21** is the textbook case. The post stalled in `ctxsys.drvdml.com_sync_index` (the `ON COMMIT` sync) on
  `log file switch (checkpoint incomplete)`, behind LGWR, 55 s at its longest. The seed had switched the 20 MB logs
  about 150 times, and the checkpoint could not keep up.
- **Free 23** shows it differently. The post waited about 61 s on `enq: CR - block range reuse ckpt`, held by CKPT,
  which waited for DBW0, itself in `db file async I/O submit` for the same minute. A `NOCACHE` LOB (the default of the
  `SEARCH_TEXT` `CLOB`) is read and written directly, which first checkpoints the dirty blocks of its range; the
  stalled statement is `UPDATE CASE_SEARCH SET SEARCH_TEXT = SEARCH_TEXT || :1`. The log-switch event is only 0.06 s
  here: on Free 23 redo size drives the stall through checkpoint pressure, not through a full log. What is measured is
  that sized redo removes it; DBW0's own I/O path (`filesystemio_options` is `none` in the image) was not examined.
- The window's redo was about 21 MB for about 70 posts (about 300 KB a post, matching the 240 KB above), roughly one
  20 MB log's worth; with 512 MB none switched.
- Reproduced twice on Free 23 and once on XE 21; sized redo removed it twice on Free 23 and once on XE 21. The 332 ms
  max on XE 21 is one post and did not recur in the window's samples.

- **Seeding** takes as long as before on Free 23, about 5.5 minutes, the commit-time indexing included; 7.8 minutes
  on XE 21.

Building the index over an existing 500k seed took 17 to 33 s on Free 23 and 95 s on XE 21 (the image's 20 MB redo
logs, with another database busy on the same disk), `ONLINE`, so the running release keeps writing; the index's tables
take 117 MB. On PostgreSQL the trigram index took 13 s to build and 87 MB.

## What the numbers say

**Every target is met at the 500k seed on PostgreSQL**, at a load of ten requests always in flight.

- **The search** is where the comments show: a p95 of 235 ms becomes 496 ms over HTTP and 425 ms in process, because
  `LIKE '%…%'` reads every case's whole text and that text is seventeen times longer. It is half the 1 s budget, so
  BUILD_PLAN's condition for pulling the vendor adapters forward is not met on PostgreSQL; the trigram index #299 adds
  for parity brings it to about 0.1 s in process.
- **On Oracle it is met.** The same search is about 0.3 s at the 50k seed and **2.0 to 2.4 s at the 500k seed**,
  every-team, against the 1 s target; an Analyst's one-team search is 0.57 s. Nothing else on Oracle comes near its
  target. BUILD_PLAN decided this case in advance: "if the 500k-comment seed pushes it past that, the v1.1 vendor
  adapters are pulled forward". They were (#299, ADR-0017), and the searches now take 0.41 to 0.74 s on Free 23 (see
  "Vendor search adapters (#299)" above). Halving the `LIKE` work instead (one scan shared by the count and the page)
  would have left about 1.2 s.
- **The list** does not read comments, and did not slow down (215 ms p95 at 50k, 190 ms at 500k: within the noise of
  a shared machine and the lower request rate).
- **The case page of a 208-comment case** is the slowest page: 390 ms p95 under load, 108 ms alone. Every comment is
  rendered from Markdown and sanitized on each view. It meets the target at this load; at twice this load it does not
  (below), and paging the comments is the lever if long threads become common.
- **The aging report** takes 0.1 to 0.3 s against a budget of 30 s.

## Pool sizing

The pool was Hikari's default, 10 a replica, never set. Three runs decide it.

**1. Pool size against load** (the 500k seed, 90 s each; `browse` users as shown plus the other three scenarios).

| Pool | Browse users | Throughput | `GET /cases` p95 | `kestrel` search p95 | 208-comment case p95 | Mean wait for a connection | Samples with a request waiting |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 5 | 5 | 39.0/s | 188 | 419 | 293 | 2.01 ms | 54 % |
| 10 | 5 | 43.9/s | 141 | 380 | 266 | 0.00 ms | 0 % |
| 20 | 5 | 44.9/s | 146 | 361 | 260 | 0.00 ms | 0 % |
| 5 | 20 | 40.8/s | 501 | 1 518 | 1 118 | 22.88 ms | 75 % |
| 10 | 20 | 54.9/s | 377 | 962 | 1 020 | 7.13 ms | 50 % |
| 20 | 20 | 62.3/s | 374 | **702** | 1 012 | 0.04 ms | 0 % |
| 5 | 40 | 42.0/s | 1 127 | 2 603 | 2 312 | 51.59 ms | 72 % |
| 10 | 40 | 56.5/s | 706 | 1 673 | 2 038 | 15.98 ms | 34 % |
| 20 | 40 | 60.8/s | 733 | 1 342 | 2 133 | 5.58 ms | 20 % |

A request takes about 14 short connection checkouts (each service call is its own transaction, and the session is read
and saved), held 8 to 12 ms on average. Five connections are too few at any load. At normal load 10 and 20 are the
same. At twice the default run's load, 20 gives 13 % more throughput and cuts the search's p95 by a quarter, because
PostgreSQL then has work for more of its cores; past that the application's CPU is the limit (the 208-comment page is
CPU-bound at 1 s whatever the pool), and no pool helps.

**2. Slow attachment downloads.** A download holds a connection for as long as it streams, by design, and up to
`app.attachments.max-concurrent-downloads` (8) may stream at once. Eight clients reading a 24 MB attachment at
300 KB/s, with the k6 mix (10 browse users) running beside them for 60 s:

| Pool | Throughput | `GET /cases` p95 | `plover audit` search p95 | Mean wait for a connection | Samples with a request waiting |
|---:|---:|---:|---:|---:|---:|
| 10 | 15.9/s | **890** | **2 887** | 48.39 ms | 86 % |
| 20 | 47.8/s | 275 | 622 | 0.06 ms | 2 % |

With 10, the eight downloads leave two connections for everyone else: the replica falls to a quarter of its
throughput and both the list and the search miss their targets.

**3. Exports** used to hold a connection too; that is fixed, not sized for (next section).

**Decision: `spring.datasource.hikari.maximum-pool-size: 20` a web replica** (`application.yml`), that is the eight
download slots plus about ten for interactive requests at a load above the expected one. Hikari keeps a pool full, so a
deployment with two replicas holds 40 sessions; the database's session limit (on Oracle, `SESSIONS` and `PROCESSES`,
or the run-time user's profile) must allow that plus the batch Jobs. The purge, importer and migration profiles set
`minimum-idle: 1`, so a single-threaded Job opens connections only as it needs them instead of filling twenty.
`deploy/README.md` ("Runtime properties", "The database pool is exhausted") has the rule for changing either number.
The scheduler pool (4) and the async executor (8, the download slots' ceiling) were not changed: no run waited on them.

## Exports and database connections (#84, #91)

`cases-list-50k.md` expected an export to hold its connection for about 0.5 s, more only with a slow client. Measured
at the 500k seed with the pool of 10, it was worse than that, and not only with slow clients:

- The rows were read inside one read-only transaction while the response was written, so the connection was held for
  as long as the client took to read 2.5 MB. Worse, the first write commits the response, and committing it saves the
  person's session (Spring Session JDBC) through a **second** connection. With as many exports in flight as the pool
  has connections, each held one and waited for another: the pool deadlocked until Hikari's 30 s acquire timeout.
- Ten concurrent exports by fast clients: five answered 500 after 30.1 s, five finished after 30.6 s. Ten clients
  reading at 20 KB/s: all ten answered 500 after 30.2 s, and a `GET /cases` made meanwhile waited 27.3 s.
- The audit trail's export (#274) is built the same way: ten concurrent ones all answered 500 after 30.5 s.

**Fixed**, rather than given a semaphore: both exports now read each page (500 rows, with its names or case numbers) in
a short read-only transaction of its own and write it to the client after that transaction has ended, which is what
Part B's "no network I/O inside a transaction" asks. The consistency is unchanged: both vendors run at READ COMMITTED,
where each statement already saw its own moment, and the audit export's end is pinned to the moment of its count.
After the fix, with the same pool of 10:

| | Before | After |
|---|---|---|
| 10 slow clients (20 KB/s, 2.5 MB each) | all 500 after 30.2 s; `GET /cases` waited 27.3 s | all 200 in 126.7 s (the clients' speed); `GET /cases` p95 0.13 s; at most 1 connection in use |
| 10 / 20 concurrent fast case exports | 5 of 10 answered 500 after 30.1 s | 20 of 20 answered 200, the slowest in 1.5 s |
| 10 / 20 concurrent audit exports (6 600 events) | 10 of 10 answered 500 after 30.5 s | 20 of 20 answered 200, the slowest in 0.5 s |

`DefaultCaseExportServiceTest` and `DefaultAuditExportServiceTest` pin the rule: the response stream counts writes made
while a transaction is open, and must count none (they fail on the old code with 25 and 11 such writes). Exports
therefore need no semaphore and no temporary file. Attachment downloads still stream inside one transaction, so their
slots count against the pool, as sized above.

## The cases-list indexes (`cases-list-50k.md`, recommendations 1 and 2)

**Not added; there is no V21.** The 500k seed adds comments, and the list reads none: its numbers did not move. The
recommendations were about the number of cases one person lists, which BUILD_PLAN keeps at 50 000. Two things measured
on the way make the case against a migration now stronger:

- **`CASE_RECORD (OPENED_AT, ID)` as recommended would not be used.** The list orders by `OPENED_AT DESC NULLS LAST,
  ID ASC`, mixed directions and explicit null order, which an ascending index cannot return in order: with it in place,
  PostgreSQL still sorted (22.5 ms against 24.4 ms without). An index that matches, `(OPENED_AT DESC NULLS LAST, ID)`,
  reads the first page in 0.4 ms. If it is ever added, it must match the order on both vendors, and Oracle needs its
  own proof that it uses it.
- **The headroom is wide.** The same statements at 500 000 cases (a scratch database seeded with the case seed's
  series raised tenfold; plans only, no page): the first page newest first 218 ms, by title 277 ms, the count 18 ms,
  the filters' `DISTINCT` 50 ms. At 50 000 they are 26, 53, 4 and 11 ms. A matching index makes the first page 4 ms
  (`OPENED_AT`) or 0.3 ms (`LOWER(TITLE)`) at either size.

So the trigger stands as `cases-list-50k.md` put it, now with a number: when the cases one person lists approach a few
hundred thousand (sorting them costs about 100 ms at 200 000), add the matching indexes in one migration for both
vendors, with an Oracle plan test. The deep page (offset 47 475) is not helped by either index; keyset paging would be.
Recommendation 3 (no cache for the filter choices) and 4 (the saved-view query) stand unchanged.

## What CI runs

The nightly `perf` job runs `PerfCasesListIT` (50k) and now `PostgresComments500kPerfIT` (500k) in one command, each in
its own JVM and container, and uploads both tables. The 500k part adds about 3 minutes (33 s of seeding, then the
scenarios) to a job that took about 3, so the job stays well inside its 30-minute limit; a regression past a target
fails it. Not in CI, and why:

- **k6.** It needs the application behind a real identity provider with redirects, and the numbers it adds (load,
  pool waits) are only meaningful on a known, quiet machine; a shared CI runner gives noise, not a gate. It stays a
  release-time run by hand, recorded here.
- **The Oracle 500k run.** Seeding alone takes about six minutes, and the run takes about eleven with the
  scenarios. It stays opt-in (`perf-500k-oracle`), like the role-conflict run; it passes since #299, so adding it to the
  nightly `perf` job is one more execution in the same command, at about a quarter of an hour of Oracle a night. That
  is left to the owner.

## Found on the way, not fixed here

- **Concurrent first sign-ins of one person answer 500.** The first k6 run, on a fresh database, sent twelve virtual
  users of one person through the sign-in at once: the application's upsert of the person inserted the row twelve
  times, and all but one failed on `UK` of `APP_USER.EXTERNAL_ID` with a 500. A person signing in from two tabs at the
  same moment can meet it too. It needs its own fix (the upsert should retry or treat the duplicate as found), as the
  other concurrency 500s were (#284, #285).
- **The importer at volume and `PeopleMatcher` batching (#173)** were not measured: they are WP-30 part b.
