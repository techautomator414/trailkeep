<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0004: Persistence rules that hold on both Oracle and PostgreSQL

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #2 (amended r3), #3 (amended r3), #4 (amended r3), #5; A.4 #37 (amended r3); Part B "Persistence"
- **Plan sections:** BUILD_PLAN.md §8 rules 2–3, §9 (Input validation), Appendix A

## Context and problem statement

One entity model and one migration set must run on Oracle (target) and PostgreSQL (local and CI). Hibernate's defaults differ between vendors in ways that silently break portability: large objects, booleans, dialect detection, and binary reads. Schema validation alone also hides drift.

## Decision drivers

- Bounded heap for attachment download on both drivers.
- DDL expectations must not change with the server the app happens to connect to.
- The schema check must be meaningful, never vacuous.
- Portability must be enforced by CI rather than by memory.

## Considered options

1. Use `@Lob` and vendor-specific DDL per database.
2. Store attachments as a single `BYTEA`/`BLOB` column read through JPA.
3. A portable subset: plain mappings, chunked binary content via a JDBC DAO, pinned dialect, and layered schema checks.

## Decision outcome

**Option 3**, as these rules:

- **No `@Lob`, ever.** Hibernate maps `@Lob String`/`byte[]` to PostgreSQL large objects (`oid`), not `TEXT`/`BYTEA`. Long text is `@Column(length = Length.LONG32)` (→ `CLOB` / `TEXT`). No `byte[]` on entities. `@Enumerated(EnumType.STRING)` always.
- **Binary content is never a JPA field.** It lives in `CASE_ATTACHMENT_CHUNK (ATTACHMENT_ID, SEQ, CHUNK ${bytes})` as **1 MiB rows**, read and written by `AttachmentContentJdbcDao`. pgjdbc materializes a whole `BYTEA` on read and Oracle BLOB streams need an open connection, so chunking is what bounds heap on both vendors. Downloads run a `StreamingResponseBody` whose DAO call opens its own `TransactionTemplate`, reads **one chunk row per query by `SEQ`**, and is bounded by a download semaphore. Uploads go to a temp file, then a synchronous ClamAV scan; infected files are persisted as `QUARANTINED`, and nothing is stored unscanned.
- **Booleans** are plain `boolean` with `hibernate.type.preferred_boolean_jdbc_type=BIT`.
- **Dialect is fixed** with `jakarta.persistence.database-product-name`, `database-major-version` and `database-minor-version`. These *override* the values Hibernate detects from the connection; they do not skip detection (that would need `hibernate.boot.allow_jdbc_metadata_access=false`). The point is that DDL expectations such as `BOOLEAN` and fetch-first syntax do not change when a 23ai server is connected to a build validated on a 19c-like image.
- **`hibernate.hbm2ddl.auto=validate`** runs against the migrated schema on PostgreSQL every PR and on the Oracle matrix on `main`. Because `validate` checks only column presence and type family, a **metadata-diff test** (JPA metamodel vs. `DatabaseMetaData`: nullability and lengths) runs alongside it.
- **Migrations land with their entities** (`V1__identity` in PR 6, then `V2`–`V5` with their WPs), so `validate` is never run against an empty entity set. Native SQL is allowed only in `*JdbcDao` classes with a comment stating why JPA cannot express it.
- Free-text `VARCHAR` is capped at 1000 characters (Oracle's 4000-byte ceiling survives CHAR semantics); longer is `${clob}`.

### Consequences

- **Good:** bounded memory on download; DDL behaves identically across servers; drift is caught on nullability and length, not just presence.
- **Bad:** attachment code is hand-written JDBC; chunk-per-query downloads cost more round trips; the metadata-diff harness is custom code to maintain; every PR that adds a table must ship an entity with it.

## Confirmation

- ArchUnit: no `@Lob`, `@Enumerated` must be `STRING`, repositories package-private (WP-07).
- Testcontainers `validate` + metadata-diff harness, PostgreSQL on every PR, Oracle xe 21 and free 23 on `main` (WP-03a, WP-03b).
- Migration-lint test (placeholders, identifier length, `VARCHAR` > 1000).
- Semgrep: no string-built SQL or non-constant `createNativeQuery`.
- WP-14 acceptance: 8 concurrent 25 MB downloads on PostgreSQL with `-Xmx256m` succeed.
