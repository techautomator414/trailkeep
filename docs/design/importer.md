<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Importer design: legacy compliance case history into Trailkeep

- **Status:** Proposed design for WP-26 (decision recorded in
  [ADR-0014](../../adr/0014-importer-sources-and-canonical-model.md)), revised after the independent review on #56
- **Builds on:** BUILD_PLAN.md §5, §6, §8, §9, §15, §16; IMPLEMENTATION_PLAN.md A.4 #33, #34, #37, Part B, Part G,
  WP-19, WP-26; ADR-0004, ADR-0005, ADR-0007, ADR-0012, ADR-0013
- **Merge order:** ADR-0013 (scanning mode, `NOT_SCANNED`, `V4_4__attachment_not_scanned`) is in #57. #57 merges
  before this design (#56), which references it.
- **Scope:** one-time migrations and delta loads during a cut-over window, from a legacy tracker held in **Microsoft
  SQL Server, Oracle or PostgreSQL**, or from **files exported** from any of them

The organization-specific parts stay outside this repository (BUILD_PLAN §15.4): the mapping file, source
connection details, extracts and reconciliation outputs live in the deploying organization's private configuration
repository. This repository ships the engine, the canonical model, the schemas, the export recipes and a fully
synthetic worked example.

Decisions marked **Proposed; owner to confirm** are the reviewer's recommended option, adopted so the design is
complete. Each is listed in §13.

## 1. Goals and non-goals

**Goals**

1. Import cases, comments, attachments, closure outcomes and (optionally) legacy change history with **zero silent
   loss**: every source row is loaded, deliberately skipped by a mapping rule, or reported as an issue.
2. Work from a live database (read-only) **or** from exported files, with the same mapping and the same canonical rows.
3. **Idempotent and restartable**: a re-run is a no-op; a crashed load resumes at the next case; a crashed extract
   restarts the unfinished entity (§4.1); a later delta run loads only what is new and reports what changed.
4. Map an arbitrary legacy data model onto Trailkeep's model **by configuration**, without code changes per
   organization.
5. Keep Trailkeep's invariants (CaseScope, the SLA anchor rule, insert-only comments, the attachment intake, audit
   with `origin = IMPORT`, both database vendors). Where an import must differ from a person using the UI, the
   difference is a named, tested exception in an import-only API (§8.1), not a bypass.

**Non-goals (v1)**

- Parsing vendor backup or dump formats (`.bak`, Data Pump `.dmp`, `pg_dump` custom format). Restore the backup into a
  scratch database of the same vendor and use the JDBC source. The same path serves raw per-table exports (§4.2).
- Two-way sync or continuous replication. Delta loads support a cut-over window, not coexistence.
- A general scripting language in the mapping (no SpEL, JavaScript or Groovy). The transform set is closed and
  reviewed (§6.3); this keeps the "importer mapping DSL beyond fixed YAML" item deferred to v1.1 as the plan says.
- Importing people's identities. People are matched to existing Trailkeep users, who arrive through the identity
  provider; unmatched people become name snapshots (§7.3). The one non-login import principal (§7.3) is not a person.
- Reproducing legacy per-user access lists. Trailkeep's visibility model is team plus sensitivity; §7.1 states how
  legacy access maps onto it and what the dry run reports.

## 2. Architecture

```
 legacy source              importer Job (profile "importer", credential TRAILKEEP_IMPORT)        Trailkeep database
 ─────────────              ──────────────────────────────────────────────────────────────        ──────────────────
 SQL Server ─┐              ┌──────────┐   ┌──────────────┐   ┌──────────┐
 Oracle     ─┼─ JDBC, one ─►│ Extract  │──►│  Canonical   │──►│ Validate │──► IMPORT_ISSUE, preview
 PostgreSQL ─┘   snapshot   │ (source  │   │  staging     │   │ + dry run│
                            │ adapter) │   │  IMPORT_STG_*│   └────┬─────┘
 CSV / TSV  ─┐              │          │   └──────────────┘        │ sign-off (authenticated screen, §3)
 JSON Lines ─┼─ files ─────►│          │──► content area      ┌────▼─────┐   CASE_RECORD, CASE_SLA, CASE_SEARCH,
 attachments ┘  (read-only) └──────────┘    (bytes + SHA-256,  │   Load   │──► CASE_COMMENT, CASE_ATTACHMENT(_CHUNK),
                                 ▲          run-scoped)  ────► │ (import  │    CLOSURE_REQUEST, AUDIT_EVENT
                      mapping file (YAML, private)             │   APIs)  │──► IMPORT_LEDGER
                                                               └────┬─────┘
                                                                    ▼
                                                            Reconciliation report
```

Three stages, each independently re-runnable, separated by tables rather than by memory:

1. **Extract**: a *source adapter* reads the legacy data in one consistent snapshot, applies the mapping's field
   transforms, and writes **canonical staging rows**. It reads each attachment's bytes **once, inside the snapshot**,
   and records their size and SHA-256 (§4.3). This is the only stage that opens a source connection or reads source
   files. It never writes domain tables.
2. **Validate (dry run)**: checks every staged row against Trailkeep's business rules and the referential rules
   between staged rows, writes `IMPORT_ISSUE` rows and a reconciliation preview with forecasts (§9), and changes
   nothing in the domain.
3. **Load**: turns valid staged rows into domain rows **through import-only APIs of the domain modules** (§8.1), one
   transaction per case, recording each target in `IMPORT_LEDGER`. It reads only staging, the content area and the
   Trailkeep database: **the load never opens a source connection**, so it needs no source credential or source
   egress, and a source decommissioned after extract does not matter. Then it writes the reconciliation report.

The **canonical staging model** (§5) is the contract between the many possible legacy shapes and the one Trailkeep
model. Adding a new source kind means writing an adapter that fills staging; the validation, load and reconciliation
code does not change.

### 2.1 Modules and runtime

- Code lives in the `importer` module (already in the module list, IMPLEMENTATION_PLAN Part B; today only its
  `package-info.java` exists). It depends on public interfaces of `casefile`, `document`, `closure`, `user` and
  `audit`. Spring Modulith verifies that it reaches nothing else. **Those interfaces do not exist yet**; §2.2 lists
  them and §12 delivers them.
- It runs as a **Kubernetes Job** (`deploy/jobs/importer-job.yaml` exists) with the `importer` profile
  (`web-application-type=none`, no OIDC, no web schedulers), the same image as the web app (BUILD_PLAN §16). The
  mapping file and any extract files arrive on a read-only volume; the run's content area is a separate writable
  volume (§4.3). The source credential and the `TRAILKEEP_IMPORT` credential arrive as Secret files (Spring
  `configtree`), never as environment text. Today the Job mounts the web app's Secret; §12 (26u) changes that.
- Because there is one image, every new runtime dependency (ADR-0014, "Dependencies") is on the web app's classpath
  and in its SBOM too, and is scanned there. Only the importer profile calls it.
- Commands: `validate-mapping`, `extract`, `dry-run`, `load`, `reconcile`, `status`, `purge-staging`. Each is one Job
  run with `--import.command=…` and `--import.run=<id>`; the run id comes from the authenticated run request (§3).

### 2.2 What exists today and what WP-26 adds

Checked against `main` at a9e87d9, plus #57.

| Need | On `main` today | Added by (§12) |
|---|---|---|
| Create a case with original dates, closed status, `LEGACY_REF` | `CaseService.create(Actor, CaseFields)` opens at `clock.instant()`, always `OPEN`; `LEGACY_REF` column exists but is not mapped on `CaseRecord` | `CaseImportService` in `casefile` (26e) |
| Show the legacy number, snapshot an unmatched owner | No column | `LEGACY_NUMBER`, `OWNER_NAME_SNAPSHOT` on `CASE_RECORD` (26d) |
| Post a comment with original time and author snapshot | `CASE_COMMENT.AUTHOR_NAME_SNAPSHOT` and nullable `AUTHOR_ID` exist; `CaseCommentService.post` uses the actor and the clock | `CaseImportService.importComment` (26f) |
| Store an attachment from the importer | `UploadIntake` is package-private; no public attachment service yet | `AttachmentImportService` in `document`, `UPLOADER_NAME_SNAPSHOT` (26g) |
| Record a closure outcome | `CASE_RECORD` has no outcome; outcome lives on `CLOSURE_REQUEST` (`V5__closure`, WP-19, not built) | `ClosureImportService` in `closure` after WP-19 (26h) |
| An actor for `CREATED_BY`, `UPLOADED_BY`, `REQUESTED_BY` (NOT NULL FKs to `APP_USER`) | No system user row | Import principal in `user`, with its ADR (26c) |
| Match legacy people | `UserDirectoryService` has no lookup by email or external id | Read-only lookups in `user` (26c) |
| `NOT_SCANNED` status | #57 (`V4_4`) | ADR-0013 |
| Audit with origin `IMPORT` | `AuditOrigin.IMPORT`, `CK_AUDIT_ORIGIN`; `ACTOR_ID` nullable, no FK | Exists |
| Unrestricted scope for non-request work | `CaseScope.system()` | Exists |

## 3. Lifecycle of an import

| Step | Who / command | What happens | Writes domain? |
|---|---|---|---|
| 0 | **Run request** on the Import runs screen | A Compliance Admin or System Admin opens a run (`IMPORT_RUN`, `REQUESTED`), naming the source id; audited | No |
| 1 | `validate-mapping` | Mapping parsed against its JSON Schema; lookups resolved; paths contained; placeholders checked (§10); each query validated (§4.1) and **test-fetched with `setMaxRows(1)`**; files and manifest checked | No |
| 2 | `extract` | Source read in one snapshot (§4.1); transforms applied; staging and content area filled, with a keyed row hash per row | No |
| 3 | `dry-run` | All rules checked; `IMPORT_ISSUE` filled; preview and forecasts produced (§9) | No |
| 4 | **Sign-off** on the Import runs screen | A Compliance Admin **other than the requester** reviews the preview, acknowledges each WARNING class (including `NOT_SCANNED`, §4.3), and approves; stored as `APPROVED_BY` (an `APP_USER` id) and audited | No |
| 5 | `load` | Loads only rows not already in `IMPORT_LEDGER`, one case per transaction; resumable | Yes |
| 6 | `reconcile` | Final report comparing source, staging and Trailkeep (§9) | No |
| 7 | `purge-staging` | Deletes the run's staging rows and content area (they hold PHI); also enforced by a TTL (§5) | No |

`load` refuses to start unless the run passed `dry-run` with zero fatal issues, is signed off, and the mapping hash
and staging have not changed since the sign-off. Error-severity issues are allowed only below the mapping's
`limits.maxErrorRate` and are always listed in the report.

**Sign-off is authenticated (Proposed; owner to confirm).** The importer profile has no OIDC, so a name typed into a
Job argument proves nothing. The run request and the sign-off are therefore made by signed-in users on an **Import
runs** screen (BUILD_PLAN §3.2: "import runs" is visible to Compliance and System Admins). Approval needs Compliance
Admin, because it authorizes writing case data; the approver must differ from the requester (checked in the service
and by a `CHECK (APPROVED_BY <> REQUESTED_BY)`). WP-25 (System screen) depends on WP-26 for "last import run", so the
Import runs screen ships inside WP-26 (26n) and the System screen links to it once WP-25 lands; there is no cycle. The
alternative, recording sign-off outside Trailkeep and saying so, is listed in §13.

### 3.1 Delta runs

A **delta run** (cut-over freeze) is a new run with a new `extract` against the same source and mapping:

- **New keys** load normally. That includes **child-only loads**: a new comment or attachment whose parent case is
  already in `IMPORT_LEDGER` attaches to the existing Trailkeep case found through the ledger, in one transaction per
  parent case with its ledger rows, search append and audit events.
- **Same key, same canonical hash**: skipped.
- **Same key, different canonical hash**: reported as `CHANGED_AFTER_IMPORT` and not overwritten (Trailkeep may hold
  edits; the person running the cut-over decides). A case that was open at first load and is **closed in the
  source** now gets the sub-code `CLOSED_IN_SOURCE` and goes on a worklist; it is closed in Trailkeep through the
  normal closure flow, not automatically, because an automatic closure would bypass the approval rule (BUILD_PLAN
  §3.2). **Proposed; owner to confirm** (§13, question 3).
- **Ledger keys absent from the new extract**: reported as `MISSING_IN_SOURCE` (WARNING). Nothing is deleted.
- **Mapping changes**: every ledger row records the mapping hash it was loaded under. A delta under a different
  mapping hash is refused unless the run sets `--import.allow-mapping-change`; with it, change detection is switched
  off for that run (any field change would otherwise flag every row) and the report says so. New and missing keys are
  still handled as above.

## 4. Sources

### 4.1 Live database (JDBC)

| | SQL Server | Oracle | PostgreSQL |
|---|---|---|---|
| Driver | `com.microsoft.sqlserver:mssql-jdbc` (MIT), new | `ojdbc17` (Oracle-FUTC, already a dependency) | `org.postgresql:postgresql` (BSD-2-Clause, already) |
| TLS | `encrypt=strict` (TDS 8.0: SQL Server 2022 or later and a driver that supports it); older servers `encrypt=true`; always `trustServerCertificate=false` and `hostNameInCertificate` | TCPS with a PKCS#12 or JKS truststore, `oracle.net.ssl_server_dn_match=true`; auto-login wallets need `oraclepki`, which is not on the classpath (pom note) | `sslmode=verify-full` with `sslrootcert` pointing at the mounted CA |
| Snapshot | `SNAPSHOT` isolation; needs `ALLOW_SNAPSHOT_ISOLATION ON` (`READ_COMMITTED_SNAPSHOT` alone is statement-level, not enough); or a readable AG secondary (`ApplicationIntent=ReadOnly`), or a database snapshot or restored copy | `SET TRANSACTION READ ONLY` (transaction-level read consistency), or `AS OF SCN :scn` on every query with one SCN | `REPEATABLE READ READ ONLY` |
| Cost of a long snapshot | Row versions held in tempdb's version store for the whole extract | Undo must cover the extract (ORA-01555 otherwise); `AS OF SCN` needs the `FLASHBACK` object privilege | Holds back the xmin horizon; vacuum on the source is blocked meanwhile |
| Streaming | Fetch size; `responseBuffering=adaptive` is the default | Fetch size; LOB prefetch | Autocommit off + fetch size; `bytea` is materialised per value (§4.3) |
| Binary columns | `VARBINARY(MAX)`, `IMAGE` | `BLOB` | `bytea`; `oid` large objects through the driver's large-object API |
| Text columns | `NVARCHAR(MAX)`, `NTEXT` | `CLOB`, `NCLOB` | `text` |

The source-account recipe (`docs/importer/source-accounts.md`) documents each cost and how to avoid it on a
production source: prefer a restored copy, a database snapshot or a readable secondary.

**Read-only: the account's grants are the control.** Nothing else in this list is a guarantee, so the recipes grant:

- `SELECT` on the named tables or views the mapping reads, and nothing else: no `EXECUTE`, no `db_datareader` on
  everything, no `SELECT ANY TABLE`;
- no database links (Oracle), no linked servers (SQL Server), no `dblink`/`postgres_fdw` and no
  `pg_execute_server_program` or `pg_write_server_files` (PostgreSQL);
- on Oracle, `FLASHBACK` on the same objects only when `AS OF SCN` is used.

Defence in depth, never relied on alone:

- The connection is opened read-only and every query runs in the snapshot transaction above, with a statement
  timeout. On PostgreSQL and Oracle the transaction refuses DML. **SQL Server has no read-only transaction**:
  `setReadOnly(true)` does not make mssql-jdbc refuse writes, and `ApplicationIntent=ReadOnly` only routes to a
  secondary. On Oracle, a function with `PRAGMA AUTONOMOUS_TRANSACTION` runs in its own transaction, outside the
  read-only one. The design does not depend on either: the grants deny `EXECUTE`, and the validator rejects calls to
  functions outside an allow-list.
- Each mapping query is **parsed** (JSqlParser, Apache-2.0 election; ADR-0014) and must be exactly one `SELECT` or
  `WITH … SELECT`. The validator rejects statement separators, `INTO`, `FOR UPDATE` and locking hints (`UPDLOCK`,
  `XLOCK`, `HOLDLOCK`, `TABLOCKX`), `OPENROWSET`/`OPENQUERY`/`OPENDATASOURCE`, Oracle `@dblink` references, and any
  function call not on a per-vendor allow-list of pure built-ins (string, date, numeric, `COALESCE`, `CAST`, JSON
  construction for exports). A query the parser cannot read is rejected, not passed.
- `validate-mapping` **test-fetches one row** (`setMaxRows(1)`, statement timeout) instead of asking for a plan:
  Oracle's `EXPLAIN PLAN` writes to `PLAN_TABLE`, and SQL Server has no `EXPLAIN` (`SHOWPLAN` needs its own
  permission).
- The security tests run the grants recipe itself on each vendor's container: the import account cannot insert,
  update, delete, execute or reach a link (§11).

**Queries are configuration, written by the operator.** Each canonical entity is filled by one query in the mapping,
so joins, filters and legacy oddities are expressed in the source's own SQL. No query text is ever built from data
and the engine never wraps or rewrites a query (so no `SELECT COUNT(*) FROM (…)`, which SQL Server also rejects when
the inner query has `ORDER BY` without `TOP`). Parameters, such as a delta watermark or an attachment key, are bind
variables declared in the mapping. Queries need no `ORDER BY`: the engine orders by canonical key itself (§5).

**Restart, not mid-entity resume.** A crashed extract restarts **per entity**: the unfinished entity's staging rows
are deleted and the entity is read again. Cross-entity consistency needs the same fixed point, so restart is allowed
only when the source is one: files, a restored copy, a SQL Server database snapshot, or an Oracle SCN still inside
undo retention (the run records the SCN). A PostgreSQL exported snapshot or a live `SNAPSHOT`/`REPEATABLE READ`
transaction dies with the connection, so a crash there restarts the whole extract. `IMPORT_RUN_CHECKPOINT` records
finished entities only, so no query needs a `WHERE key > :last` bind and no collation has to agree with Java's.

**Time zones.** The mapping declares `source.timezone` (required).

- Zone-less values (`DATETIME`, `DATETIME2`, `TIMESTAMP`, Oracle `DATE`, which carries a time) are read with
  `getObject(…, LocalDateTime.class)`, never `getTimestamp`, which applies the JVM zone, then placed in the declared
  zone. `DATETIMEOFFSET` and `TIMESTAMP WITH TIME ZONE` keep their offset (`OffsetDateTime`). For Oracle
  `TIMESTAMP WITH LOCAL TIME ZONE` the adapter sets the session time zone to UTC first.
- SQL Server `DATETIME` is accurate to about 3.33 ms; the source-equivalence test compares at that precision.
- DST gaps and overlaps follow `source.dstAmbiguity` (`earlier` | `later` | `reject`). The default stays `reject`, so
  ambiguity is never guessed silently. Years of zone-less data contain one repeated hour per year, so expect a few
  `DST_AMBIGUOUS` issues per year of data; with `maxErrorRate: 0.0` they block the load. The worked example
  therefore uses `earlier`, which turns each into a WARNING the sign-off acknowledges.

### 4.2 Exported files

**Files must be the per-entity query outputs.** A file source is bound per entity (`entities.<name>.file`, §6.1),
and each file holds exactly the rows and columns that entity's query returns: the recipes run the mapping's own
queries. Raw per-table dumps cannot be joined by the importer; load them into a scratch database of any supported
vendor and use the JDBC source. That is the supported path for raw exports, and the one dump formats already use.

Supported: **CSV/TSV** (RFC 4180 plus a declared delimiter, row terminator, quote, escape, header, null token and
encoding; parsed with Apache Commons CSV) and **JSON Lines** (one object per line). Encodings: UTF-8 with or without
BOM; UTF-16LE/BE with or without BOM when declared (a BOM, if present, is consumed); Windows-1252 and ISO-8859-1 when
declared. A file manifest (`manifest.yaml`) lists each file, its entity, row count and SHA-256, and the **snapshot
the export was taken in** (§4.2.1), so the extract can prove the files are complete, unchanged and mutually
consistent.

#### 4.2.1 One snapshot for all files

Each export command normally runs in its own transaction, so comments written between the case export and the
comment export would be orphans or missing. The recipes therefore use one fixed point and record it in the manifest:

- **SQL Server:** export from a database snapshot (`CREATE DATABASE … AS SNAPSHOT OF …`) or a restored copy; every
  `bcp`/`sqlcmd` connection then reads the same state.
- **Oracle:** one SQL\*Plus or SQLcl session with `SET TRANSACTION READ ONLY` before the first spool, or `AS OF SCN`
  with one SCN on every query.
- **PostgreSQL:** one `psql` session: `BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;`, every `\copy`, then
  `COMMIT;` (`\copy` runs in the session's transaction). Parallel sessions use `pg_export_snapshot()` and
  `SET TRANSACTION SNAPSHOT`.

A frozen source (cut-over freeze, no writers) is also a fixed point; the manifest says which was used.

#### 4.2.2 Export recipes (`docs/importer/export-recipes.md`)

Every command in the recipes is run by a test against the vendor container (§11), so the recipe documents what was
verified rather than what a manual says.

- **SQL Server.** `bcp -c` does not write RFC 4180 CSV: it neither quotes nor escapes and writes no header, and in
  character mode it writes an empty string as a single `0x00` byte and NULL as an empty field. The recipe therefore
  exports **JSON Lines**: `SELECT (SELECT c.* FOR JSON PATH, WITHOUT_ARRAY_WRAPPER, INCLUDE_NULL_VALUES) FROM …` run
  through `sqlcmd -y 0 -h -1` with `SET NOCOUNT ON` (`-y 0` stops `sqlcmd` from splitting and truncating long
  values). Each row is one JSON object on one line, because JSON escapes line breaks. If CSV is required, the recipe
  uses `bcp` with a field and row terminator that cannot occur in the data, declared in the manifest, and the file
  source treats a lone `0x00` field as the empty string. The recipe does not rely on `bcp -C 65001` on Linux; the
  recipe test records the encoding the tools actually wrote. Attachments are **not** exported with `bcp -n`, which
  writes a length prefix before each value; use the JDBC source, or a `bcp` format file with prefix length 0 if files
  are required.
- **Oracle.** SQL\*Plus truncates CLOB output at `SET LONG`, which defaults to 80, so the recipe sets `LONG`,
  `LONGCHUNKSIZE`, `LINESIZE`, `PAGESIZE 0`, `TRIMSPOOL ON`, `HEADING OFF` and `FEEDBACK OFF`. It pins `NLS_LANG`
  (or the SQLcl JVM encoding) to UTF-8, and `NLS_DATE_FORMAT`, `NLS_TIMESTAMP_FORMAT`, `NLS_TIMESTAMP_TZ_FORMAT` and
  `NLS_NUMERIC_CHARACTERS` to ISO values, so file and JDBC extracts hash the same. JSON Lines come from
  `SELECT JSON_OBJECT(… RETURNING CLOB) FROM …` where the source version supports it; SQLcl `SET SQLFORMAT json`
  writes one wrapped document, not JSON Lines, and is not recommended. BLOBs are read **through the JDBC source
  only**: `DBMS_LOB` to files writes on the database server through a directory object, which a read-only account
  cannot do.
- **PostgreSQL.** `psql -c "\copy (<query>) TO 'cases.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')"` inside the
  snapshot above. `bytea` is exported as hex inside the CSV (`\x…`, the default `bytea_output`), which the CSV adapter
  decodes when the mapping declares the column `binary: hex`, or read through JDBC. `lo_export`/`\lo_export` apply only
  to real `oid` large objects, never to `bytea`.

File sources go through the **same mapping file**: the `source` block says `kind: files`, and each entity names its
file. An organization can trial with exports and run the cut-over against the live database, or the other way
round, and get identical canonical rows (the test suite checks this, §11).

### 4.3 Attachments

An attachment row points at its bytes in one of three ways:

- a **content query**: an operator-written single-row query with the attachment key as bind variables
  (`content.query`), so metadata rows are read without their bytes and an over-cap file is never fetched;
- a **file path** relative to a mounted folder (`attachments.root`);
- a **content id** resolved through the manifest.

**Bytes are captured at extract, inside the snapshot (B1).** For content queries, the extract streams each value
once into the run's **content area**, a run-scoped directory on a dedicated writable volume, named by SHA-256, and
records size and SHA-256 in `IMPORT_STG_ATTACHMENT`. For path and content-id sources, the extract hashes the file in
place and records size and SHA-256; at load the file is hashed again while it is read, and a mismatch is a
`CONTENT_CHANGED` issue that rolls back that case's transaction. Either way, the bytes the dry run validated are the
bytes that are loaded, and the load never opens a source connection. The content area holds PHI: the volume must be
encrypted at rest (a platform responsibility, as in BUILD_PLAN §9 "Data at rest"), and the content area is deleted by
`purge-staging` and by the staging TTL (§5).

**Paths.** Legacy paths are often Windows UNC or backslash paths. The mapping may declare `attachments.stripPrefix`
(removed literally, case-insensitively); `\` is normalised to `/`; then the path is resolved under the root, and
`toRealPath()` must start with the root's real path. Absolute paths, `..` segments and symlinks that leave the root
are rejected (`PATH_OUTSIDE_ROOT`). The same rule applies to every file the mapping names, including lookup CSVs (§6.1).

**Size.** `app.attachments.max-bytes` defaults to 25 MB (`AttachmentProperties`), and legacy scans are often larger.
The importer has its own cap, `import.attachments.max-bytes` (default: the app's cap). In `clamd` mode it may not
exceed clamd's `StreamMaxLength` (100 MB by default per `deploy/base/configmap.yaml`); startup fails if it does. A file
over the importer cap is not loaded and becomes an `ATTACHMENT_TOO_LARGE` ERROR issue carrying its size; with the
default `maxErrorRate` that blocks the load until the operator raises the cap or accepts the skip explicitly. Files
above the app's cap but within the importer's are stored and downloadable; the download path streams chunks and does
not depend on the size. On PostgreSQL the driver holds a whole `bytea` value in memory, so the content query fetches
one value at a time and the Job's memory limit bounds the importer cap there; the recipe says so and prefers path
sources for large files.

**Intake and scanning.** Every attachment goes through `document`'s intake (`UploadIntake`), exactly like a browser
upload: size cap, detected type against the allow-list, extension cross-check, refusal of disguised executables,
image header cap, SHA-256, and the deployment's scanning mode (ADR-0012, ADR-0013). A file that fails the type checks
is not loaded and becomes an issue. In `clamd` mode an infected file is stored `QUARANTINED` and reported (BUILD_PLAN
§15.2), and a scanner outage stops the load cleanly with the run resumable. The import entry point (§8.1) scans the
content-area or mounted file directly and re-checks its SHA-256 while writing chunks, so attachments need no copies
on the Job's `emptyDir`; the content-area volume is sized for the run's source-query attachments instead (26u).

**`external` scanning mode: imported attachments follow the app's mode (owner decision, 2026-11-05).** In `external`
mode imported attachments are stored `NOT_SCANNED`, exactly like uploads, and are downloadable (ADR-0013). Legacy
files never pass the upstream scanner on their way in, so this is the risk ADR-0013 names; ADR-0014 records it as an
**owner-accepted risk**. To make it visible before anything is stored, the dry run reports the **number and total
size of attachments that will be stored `NOT_SCANNED`** as a WARNING that the sign-off must acknowledge (§3, §9). The
importer reads the mode from the same ConfigMap as the web app (`APP_ATTACHMENTS_SCANNING`), so it cannot differ, and
each imported attachment's audit entry records the mode in force (`ScannedUpload.scanMode()`, ADR-0013). The Job
manifest gets an `external` variant without the `clamav-ca` volume, mirroring `deploy/overlays/external-scanning`
(26u). There is no always-scan rule for imports.

## 5. Canonical staging model

Vendor-neutral tables in the Trailkeep database, one per canonical entity, keyed by `(RUN_ID, LEGACY_KEY)`. Each
table ships with the entity that uses it, in the PR that first writes it (§12); staging rows are JPA entities
(`Length.LONG32` for long text, no `@Lob`), so the only native SQL is the source query DAO (ADR-0014, Part B
amendment).

| Table | Holds | Notes |
|---|---|---|
| `IMPORT_RUN` | Run: source id, requester, approver, status, mapping hash, source fingerprint (snapshot, SCN or manifest hash), counts, acknowledged warning classes, staging expiry | BUILD_PLAN §5.1 "Supporting tables" lists it |
| `IMPORT_STG_PERSON` | Legacy people: key, display name, email, external id hint | Only to resolve owners and authors |
| `IMPORT_STG_CASE` | Title, description, subject, case type key, team key, owner key, priority, sensitivity, ACL flag, status, identified on, opened at, closed at, closure outcome and summary, legal hold, legacy number | One row per legacy case |
| `IMPORT_STG_COMMENT` | Case key, author key or name, written at, body, body format | Insert-only target |
| `IMPORT_STG_ATTACHMENT` | Case key, file name, size, SHA-256, content pointer (content-area name, path or content id), uploaded at, uploader key | Bytes are in the content area or the mounted folder, never in staging |
| `IMPORT_STG_EVENT` | Optional legacy history: case key, at, who, kind, old value, new value | §7.5 |
| `IMPORT_ISSUE` | Run, stage, entity, legacy key, severity (`FATAL`/`ERROR`/`WARNING`), code, numeric detail (for example a size) | Codes, never values (§10) |
| `IMPORT_LEDGER` | `(SOURCE_ID, ENTITY, LEGACY_KEY)` unique → `CASE_ID` (FK), `TARGET_ID`, canonical hash, written hash, mapping hash, run id, loaded at | Idempotency across runs; insert-only |
| `IMPORT_RUN_CHECKPOINT` | Run, stage, entity finished | Restart per entity (§4.1) |

`IMPORT_LEDGER.CASE_ID` references `CASE_RECORD` and is indexed, per the FK policy (A.4 #35); `TARGET_ID` is the
comment, attachment or case id and has no FK because it is polymorphic by `ENTITY`. `IMPORT_RUN.REQUESTED_BY` and
`APPROVED_BY` reference `APP_USER`.

**Keys.** A canonical key is a list of one or more components (`key: case_id` or `key: [region, case_seq]`, §6.1),
each canonicalised: integral numbers as plain decimal (`BigDecimal.stripTrailingZeros()`, so Oracle `1` and `1.0` and
CSV `"1"` agree; a non-integral numeric key is an issue); GUIDs as lower-case hyphenated; strings exactly as read,
unless the entity declares `keyCase: insensitive` (for SQL Server case-insensitive collations), in which case they are
lower-cased (`Locale.ROOT`). Components are joined with `/`, with `/` and `\` escaped. The engine sorts staging by
canonical key itself, so no source ordering or collation matters.

**Length budget.** `LEGACY_REF VARCHAR(100)` holds `<source id>:<canonical key>`. `source.id` is at most 16
characters (`[a-z0-9-]`), and `validate-mapping` plus the extract reject any key that would make `LEGACY_REF` exceed
100 characters (`KEY_TOO_LONG`). `LEGACY_REF` is the identity; the number people know is `LEGACY_NUMBER` (§7.1).

**Hashes are keyed.** Row hashes, the reconciliation checksums and the `hash` transform use **HMAC-SHA-256 with a
server-side key** (`import.hash-key`, a Secret file of at least 32 bytes, the same rule as `AuditHmac`), never plain
SHA-256: titles and names are short and a plain hash is dictionary-reversible (BUILD_PLAN §9, "Audit"). It is a
separate key from the audit key, so the importer never holds the audit key. Rotating it makes every delta row look
changed, so a rotation is run with `--import.allow-mapping-change` semantics (§3.1). Attachment SHA-256 values are
plain, as in `CASE_ATTACHMENT.SHA256`: they hash whole files, not short text.

**Grants: a separate `TRAILKEEP_IMPORT` credential (Proposed; owner to confirm).** Staging in the Trailkeep schema
would otherwise be readable by `TRAILKEEP_APP`, so every web pod could read every staged restricted description
outside `CaseScope`. The importer Job connects as a fourth database user, `TRAILKEEP_IMPORT` (`${import_user}` in
`R__grants.sql`, `APP_DB_IMPORT_USER` in the ConfigMap; it must exist like `TRAILKEEP_PURGE`, and a deployment that
never imports leaves it locked):

| Object | `TRAILKEEP_IMPORT` | `TRAILKEEP_APP` |
|---|---|---|
| `IMPORT_STG_*`, `IMPORT_RUN_CHECKPOINT` | S I D | none |
| `IMPORT_ISSUE` | S I D | S (codes and counts only, for the screen) |
| `IMPORT_RUN` | S I, U of status, counts, fingerprint and expiry columns | S I (run request), U of `APPROVED_BY`, `APPROVED_AT` and the acknowledgement columns |
| `IMPORT_LEDGER` | S I (no U, no D) | S |
| Domain tables the load writes | The same insert grants as `TRAILKEEP_APP` on `CASE_RECORD`, `CASE_SLA`, `CASE_SEARCH` (plus U for the append), `CASE_COMMENT`, `CASE_ATTACHMENT`, `CASE_ATTACHMENT_CHUNK`, `CLOSURE_REQUEST`, `AUDIT_EVENT`; S on `APP_USER`, `TEAM`, `APP_USER_TEAM`, configuration; sequence `USAGE` | Unchanged |

The "insert-only" rules carry over unchanged: `TRAILKEEP_IMPORT` has no UPDATE or DELETE on `CASE_COMMENT`,
`CASE_ATTACHMENT_CHUNK`, `AUDIT_EVENT` or `IMPORT_LEDGER`. `DatabaseGrantsIT` gains the new column. This changes
BUILD_PLAN §8 rule 7 (three users become four, and "importer staging tables" move from the app user to the import
user), so the PR records it under Plan deviations.

**Staging TTL.** Every run has an expiry (`import.staging-ttl`, default 14 days from extract). Every importer command
first purges the staging rows and content area of any run past its expiry, signed off or not, and records the purge
on the run. An abandoned run therefore does not keep PHI indefinitely. The TTL is checked again by `status`.

## 6. The mapping file

A private YAML file, validated against a JSON Schema shipped in the repository
(`src/main/resources/importer/mapping-v1.schema.json`). Unknown keys fail validation. A synthetic, complete example
lives in `src/test/resources/importer/example-mapping.yaml` against a synthetic legacy schema (§11).

### 6.1 Shape

```yaml
version: 1
source:
  id: legacy1                      # <= 16 chars; the LEGACY_REF prefix; never change it between runs
  kind: jdbc                       # or: files
  vendor: sqlserver                # sqlserver | oracle | postgresql (jdbc only)
  url: ${import.source.url}        # placeholders only here and in user; only import.source.* names
  user: ${import.source.user}      # the password is a Secret file, never named in the mapping
  timezone: America/Chicago        # how zone-less timestamps are read
  dstAmbiguity: earlier            # earlier | later | reject (schema default: reject)
limits:
  maxErrorRate: 0.0                # fraction of rows per entity allowed to fail validation
  batchSize: 500
lookups:                           # value tables, inline or from a private CSV under the mapping directory
  status:   { "Open": OPEN, "In Review": OPEN, "Closed": CLOSED, "Void": CLOSED }
  priority: { "1": HIGH, "2": NORMAL, "3": NORMAL }
  outcome:  { "Substantiated": SUBSTANTIATED, "Unfounded": UNSUBSTANTIATED }
  team:     { file: lookups/teams.csv, key: legacy_unit, value: trailkeep_team }
entities:
  case:
    query: |
      SELECT c.case_id, c.case_no, c.subject_line, c.details, c.unit, c.assignee, c.state, c.result,
             c.opened, c.closed, c.found_on, c.confidential, c.acl_entries, c.hold_flag
      FROM dbo.cases c
    # file: cases.jsonl              # for kind: files, instead of query
    key: case_id                     # or [colA, colB]
    keyCase: sensitive               # or insensitive
    fields:
      legacyNumber: case_no
      title:        { trim: subject_line, maxLength: 200, onOverflow: truncateWithIssue }
      description:  { coalesce: [details, "(no description in the legacy record)"],
                      maxLength: 20000, onOverflow: continueInComment }
      teamKey:      { lookup: team, from: unit, onMissing: fail }
      ownerKey:     assignee
      status:       { lookup: status, from: state, onMissing: fail }
      closureOutcome: { lookup: outcome, from: result, onMissing: default, default: UNSUBSTANTIATED }
      priority:     { const: NORMAL }
      sensitivity:  { when: { column: confidential, equals: "Y" }, then: RESTRICTED, else: STANDARD }
      legacyAcl:    { when: { column: acl_entries, notBlank: true }, then: true, else: false }
      legalHold:    { when: { column: hold_flag, equals: "Y" }, then: true, else: false }
      openedAt:     { timestamp: opened }
      closedAt:     { timestamp: closed }
      identifiedOn: { date: { coalesce: [found_on, opened] } }
      caseTypeKey:  { const: general-review }
  comment:
    query: SELECT note_id, case_id, author, created, body FROM dbo.notes
    key: note_id
    parent: { entity: case, columns: [case_id] }
    fields:
      authorKey: author
      writtenAt: { timestamp: created }
      body:      { text: body, format: plain }   # plain is Markdown-escaped; markdown is sanitized on display
  attachment:
    query: SELECT file_id, case_id, file_name, size_bytes, uploaded, uploader FROM dbo.files
    key: file_id
    parent: { entity: case, columns: [case_id] }
    fields:
      fileName:    file_name
      uploadedAt:  { timestamp: uploaded }
      uploaderKey: uploader
      content:     { query: "SELECT content FROM dbo.files WHERE file_id = ?", size: size_bytes }
                   # or { path: rel_path } under attachments.root, or { contentId: blob_ref } via the manifest
attachments:
  root: /var/lib/trailkeep/import/files
  stripPrefix: '\\fileshare\cases\'            # synthetic; removed before resolving under root
people:
  query: SELECT user_id, display_name, email FROM dbo.users
  key: user_id
  match: [ externalId, email ]                 # in order; an ambiguous email match is an issue, not a guess
  unmatched: snapshot
defaults:
  owner: { byTeam: lookups/default-owners.csv } # eligible owner per team for open cases (§7.3)
visibility:
  onUnrepresentableAcl: restricted             # restricted | standard | fail
retention:
  pastPurge: skip                              # skip | import; required, no default (§7.1)
```

Lookup and default-owner CSVs are resolved under the mapping's directory with the containment rule of §4.3.

### 6.2 Field rules

Every target field of the canonical entity must be either mapped or covered by a declared default. A missing mapping
fails `validate-mapping`, so "forgot a field" cannot become "silently null". Required Trailkeep fields
(`title`, `description`, `teamKey`, `caseTypeKey`, `status`, `openedAt`, `identifiedOn`, and `closedAt` and
`closureOutcome` for closed cases) have no implicit defaults. A description over 20 000 characters (the
`CaseFields` cap) either fails or, with `onOverflow: continueInComment`, keeps its first 20 000 characters and
carries the rest in the case's first imported comment, with an issue.

### 6.3 Transforms (closed set)

`column` · `const` · `trim` · `upper`/`lower` · `coalesce` · `concat` (with separator) · `lookup` (with `onMissing:
fail | default | issue`) · `when/equals/in/matches/notBlank → then/else` · `regexExtract` (RE2 semantics through
re2j, linear time; the pattern is validated at `validate-mapping`) · `timestamp` / `date` (with `format` for text
sources, in the declared zone) · `number` · `binary` (`hex` | `base64`, for file sources) · `maxLength` (with
`onOverflow: fail | truncateWithIssue`, plus `continueInComment` for descriptions) · `text` (with `format: plain |
markdown | html`) · `hash` (keyed HMAC, §5, for pseudonymous keys).

Transforms are pure functions over one source row plus lookups, so they are unit-tested exhaustively and the same
mapping produces the same canonical row from a database or from its exported file.

### 6.4 Text normalisation

Every text value passes one normalisation step before any transform sees it, with an issue code when it changes
something (`TEXT_NORMALISED`, WARNING):

- strip `U+0000` (PostgreSQL rejects it in `text`; SQL Server data and `bcp` files contain it);
- replace lone surrogates and other invalid UTF-16 with `U+FFFD`;
- remove C0 control characters except `\t`, `\n` and `\r`, and normalise `\r\n` and lone `\r` to `\n`.

`text` formats then apply. `CASE_COMMENT.BODY` and the description hold Markdown that is rendered and sanitized on
display (`V4_1__comment.sql`, `MarkdownRenderer`), so:

- `plain` is **Markdown-escaped** at import (`\` before `*`, `_`, `` ` ``, `#`, `[`, `]`, `<`, `>`, `|`, and before a
  leading `1.`, `-` or `+`), so legacy text such as `1.` or `*` reads as typed;
- `markdown` is stored as is and goes through the existing sanitizer on display, which makes HTML inert;
- `html` is reduced to text with the existing OWASP sanitizer and an empty policy, then Markdown-escaped. It is never
  stored as markup.

## 7. Mapping onto the Trailkeep model

### 7.1 Cases

- **Numbering.** Imported cases get **new Trailkeep numbers** from `SEQ_CASE_NUMBER` in the one
  `PREFIX-YEAR-SEQUENCE` scheme. The year is the **load** year, as for any case created that day, so a 2019 case
  becomes `PREFIX-2026-…`; its original dates stay on the record. Keeping legacy numbers was considered and rejected
  (ADR-0014, "Considered options"): it collides with the format and with numbers issued after go-live.
- **Legacy number.** `CASE_RECORD` gains `LEGACY_NUMBER VARCHAR(100)` (26d), shown on the case page as "Legacy
  number"; `LEGACY_REF` stays the internal identity and is not shown. The legacy number is appended to `CASE_SEARCH`
  text when the search row is built, so it is searchable.
- **Status.** `OPEN` and `CLOSED` only. A legacy "pending approval" maps to `OPEN`; the closure flow restarts in
  Trailkeep. `ARCHIVED` is never imported.
- **Closed cases** import as `CLOSED` with their real `CLOSED_AT`, `CASE_SLA.SLA_STATE = NOT_APPLICABLE` and null due
  dates (BUILD_PLAN §6). `CASE_RECORD` has no outcome column: the outcome lives on `CLOSURE_REQUEST` (BUILD_PLAN
  Appendix A, `V5__closure`, WP-19). An imported closure is therefore stored as one `APPROVED` `CLOSURE_REQUEST`
  written by `closure`'s import API (26h): outcome from the mapping (validated against the case type's outcomes),
  resolution summary from the mapping or "(no resolution summary in the legacy record)", `REQUESTED_AT` and
  `DECIDED_AT` from the legacy closure time, `REQUESTED_BY` the import principal, and `APPROVER_ID`/`DECIDED_BY` the
  run's approver. That satisfies `CK_CLOSURE_SOD` (`REQUESTED_BY <> APPROVER_ID`) without weakening it, and a marker
  column added by 26h labels the request "Imported closure" so nobody mistakes it for a Trailkeep approval. WP-26
  therefore depends on WP-19.
- **Open cases** get their SLA from the case type's policy computed from the **original** dates with the one anchor
  rule (ADR-0007), and their state as of load time, so the first aging report is honest. The dry run forecasts how
  many will be At Risk or Overdue on day one, so management can be briefed (BUILD_PLAN §15.2).
- **Go-live alerts (Proposed; owner to confirm).** On its first run the SLA evaluator would send At Risk and Overdue
  mail for every imported open case. The load therefore **pre-seeds `ALERT_LEDGER`** for each imported open case with
  the alerts its load-time state would trigger in the current cycle and period, so the evaluator sends no wave;
  later transitions and overdue repeats alert normally. This needs WP-21's ledger and gets its own ADR in that PR
  (26v). The alternative is a dated `app.alerts.quiet-until` (§13).
- **Retention (Proposed; owner to confirm).** The retention job (WP-24) archives closed cases older than the case
  type's `RETENTION_YEARS`, and purge then deletes their content. Imported cases already **past purge** would be
  deleted right after the load. The mapping must therefore choose `retention.pastPurge` explicitly (no default):
  `skip` (proposed; such cases count as "skipped by rule") or `import`. Cases mapped with `legalHold: true` are always
  imported, with `LEGAL_HOLD` set by the import principal and a fixed reason, so purge skips them. The dry run reports
  how many cases will be archived and how many are past purge, using WP-24's rule, so this part lands after WP-24.
- **Inactive case types.** Closed cases may use an inactive case type, as legacy closed history often does. Open
  cases need an active one; otherwise the case is an issue and the mapping remaps it.
- **Visibility.** Team and sensitivity are mapped explicitly; an unmapped team is a fatal issue unless `onMissing:
  default` names a team. Trailkeep's model cannot express legacy per-user access lists: a `RESTRICTED` case is still
  visible to its owner, the team's Managers and Compliance Admins, and a `STANDARD` case to the whole team. So
  instead of promising that access never widens, the dry run **reports the mapped visibility per case** (team,
  sensitivity, how many cases had a legacy ACL), and `visibility.onUnrepresentableAcl` decides cases whose
  `legacyAcl` is true: `restricted` (default), `standard` or `fail`.
- **Search.** `CASE_SEARCH` is built once per case from title, description, legacy number and all imported comments,
  through `casefile`'s search writer, inside the case's transaction.

### 7.2 Comments

Imported through `casefile`'s import API (`importComment`, 26f) with their **original timestamp** and an **author
snapshot** in `AUTHOR_NAME_SNAPSHOT` (`AUTHOR_ID` null when the author has no Trailkeep account). Bodies are
normalised and formatted as in §6.4. The 20 000-character cap (`CaseComment.MAX_BODY`) applies: longer bodies are split
into numbered parts with an issue, or fail, as the mapping declares.

### 7.3 People and the import actor

People are **never created** by the importer. `people.match` resolves a legacy person to a Trailkeep user by external
id or email, through read-only lookups added to `user` (26c).

- **Expect a low match rate.** `APP_USER` rows are created at first sign-in (`UserSignInService`), and before go-live
  most staff have never signed in. The dry run reports the **match rate** per role (owners of open cases, owners of
  closed cases, authors, uploaders). The runbook recommends a sign-in drive before the final load.
- **Email is not unique** in `APP_USER`. Matching is case-insensitive, and an email that matches more than one user
  is an `AMBIGUOUS_PERSON` issue, never a guess.
- **Eligibility.** A matched owner of an open case must be able to own cases in the mapped team (Analyst or Manager,
  `CasePermissionService.mayOwn`) and not be disabled; otherwise the case falls back as if unmatched.
- **Unmatched authors and uploaders** become name snapshots (`AUTHOR_NAME_SNAPSHOT`; `UPLOADER_NAME_SNAPSHOT`, added
  by 26g; `UPLOADED_BY` is the import principal).
- **Unmatched owners of open cases** get the team's default owner from `defaults.owner.byTeam`, who must be eligible;
  the dry run fails if a team with open cases has none. The legacy owner's name goes to `OWNER_NAME_SNAPSHOT`.
- **Unmatched owners of closed cases**: `OWNER_ID` is the import principal and `OWNER_NAME_SNAPSHOT` holds the legacy
  name, shown on the case page. The principal can never act on a case, so a reopen must reassign first; WP-19's
  reopen therefore has to check owner eligibility (a note for WP-19).

**The import actor (Proposed; owner to confirm).** `CASE_RECORD.CREATED_BY`, `UPDATED_BY` and `OWNER_ID`,
`CASE_ATTACHMENT.UPLOADED_BY` and `CLOSURE_REQUEST.REQUESTED_BY` are NOT NULL foreign keys to `APP_USER`, and there
is no system user. The design adopts the reviewer's first option: **one seeded, non-login import principal**, an
`APP_USER` row with a reserved external id no identity provider issues, `DISABLED` set (sign-in already refuses
disabled users, `UserSignInServiceImpl`), no team or global roles, and therefore no access to anything. Because it is a
user that does not come from the identity provider, it needs its own ADR, written with 26c, which also decides how it
is seeded portably (migration or an idempotent `user` service call). The other option, the run's approver as
`CREATED_BY`, was not chosen: it would make a real person the owner of every unmatched closed case, giving them the
owner relationship to restricted cases they never handled, and it cannot be `REQUESTED_BY` on a closure they also
approve.

### 7.4 Audit

Every created case, comment, attachment and closure writes one `AUDIT_EVENT` with `ORIGIN = IMPORT`, the import
principal as actor (`ACTOR_NAME_SNAPSHOT` "Importer"), the run id and approver id in the details, and the **load
time** as its timestamp. Descriptions, subjects and comment bodies are redacted as for any event (`@Redacted`, HMAC).
The original legacy timestamps live on the imported rows themselves (`OPENED_AT`, `CLOSED_AT`, comment `CREATED_AT`,
`UPLOADED_AT`). The audit trail therefore stays true: it records when Trailkeep learned each fact, not a back-dated
claim. The run request and sign-off are audited by the screen (§3).

### 7.5 Legacy history (optional)

If the legacy system kept a change history, the mapping can fill `IMPORT_STG_EVENT`. In v1 these load as a single
read-only **"Imported history"** comment per case (a compact, dated list, Markdown-escaped, split at the comment cap),
so nothing is lost and the History tab stays a record of Trailkeep's own audit. A structured imported-history table is
a v1.1 candidate if users need to filter it (§13, question 1).

## 8. Load: correctness under failure

### 8.1 Import-only APIs

The load cannot use the UI services: `CaseService.create` takes a real user's `Actor`, opens at the current instant,
always creates `OPEN`, rejects inactive case types and owners who are not Analyst or Manager in the team, and has no
`LEGACY_REF`, closure or outcome; `CaseCommentService.post` stamps the actor's name and the current time; and
`UploadIntake` is package-private. WP-26 therefore adds import-only APIs, each in its own module, each called only by
`importer` (ArchUnit rule):

| API | Module | Does |
|---|---|---|
| `CaseImportService` | `casefile` | Create an imported case with original dates, `OPEN` or `CLOSED`, `LEGACY_REF`, `LEGACY_NUMBER`, owner snapshot, legal hold; its `CASE_SLA` row; add an imported comment with timestamp and author snapshot; build the search text once |
| `AttachmentImportService` | `document` | Run the intake on a content-area or mounted file with the importer cap, verify the expected SHA-256 while writing chunks, store with uploader snapshot |
| `ClosureImportService` | `closure` | Write the `APPROVED` imported closure request (§7.1) |
| Import principal, person lookups | `user` | §7.3 |

**Rules they keep:** `CaseScope` (they run under `CaseScope.system()`, which exists for non-request work, and never on
a request thread); the redaction policy for audit; the same validation of sizes, enums, the type allow-list and
scanning as uploads; insert-only comments and chunks; the SLA anchor rule; one transaction per case with its audit.

**Rules they relax, deliberately and only for imports:** the case opens at the legacy `OPENED_AT`, not now; it may be
created `CLOSED`; a closed case may use an inactive case type; a closed case's owner may be the import principal; the
attachment cap is the importer's. Each relaxation has a test, and nothing else is relaxed.

### 8.2 Transactions and idempotency

- **One case per transaction.** Each case is loaded with its SLA row, closure request, search row, comments,
  attachments, ledger rows and audit events in **one transaction**, so a case is either fully in or absent; a crash
  between cases loses nothing, and the next `load` resumes at the first case not in `IMPORT_LEDGER`. Child-only delta
  loads (§3.1) use the same unit.
- **Idempotency.** `IMPORT_LEDGER (SOURCE_ID, ENTITY, LEGACY_KEY)` is unique and written in the same transaction as
  the domain rows, so a key is loaded at most once even if two loads run by mistake (the second fails on the unique
  key and that case is skipped). A run lock (one `IMPORT_RUN` per source in `LOADING`, claimed by a conditional
  update) also refuses a second concurrent `load` for the same source.
- **Attachments before the transaction.** Each attachment is scanned (in `clamd` mode) before the case's transaction
  opens, because scanning can take seconds and network I/O stays outside transactions (Part B). The transaction then
  writes the chunks from the same file and checks the SHA-256 again. A scanner outage stops the load cleanly with the
  run resumable, exactly as uploads fail closed.
- **Memory.** Rows are streamed; no stage holds more than one batch. Attachments use the 1 MiB chunk DAO.

## 9. Reconciliation report

Produced by `dry-run` (preview) and `reconcile` (final), as CSV and an accessible HTML page on the Job's output
volume, holding counts and keyed hashes only, never field values. Legacy keys in the CSV are written with the `= + -
@` prefix rule of BUILD_PLAN §9, through one shared CSV writer in `shared` (none exists yet; the importer report or
WP-22, whichever lands first, adds it).

- **Per entity:** rows in source (rows streamed by the extract, or the manifest count for files; plus an optional
  operator-written `countQuery` cross-check) · staged · loaded · skipped by rule · failed (by issue code) · already
  loaded in an earlier run · `MISSING_IN_SOURCE` · `CHANGED_AFTER_IMPORT`.
- **Per case status and team:** source counts vs Trailkeep counts; mapped visibility (team, sensitivity, legacy-ACL
  cases and how `onUnrepresentableAcl` handled them).
- **Dates:** min and max `openedAt` / `closedAt` / comment `writtenAt`, source vs Trailkeep.
- **Checksums, each against its own kind.** At load, each ledger row records two keyed hashes: the **canonical hash**
  (the staging row) and the **written hash** (the values as written to Trailkeep, after owner substitution,
  truncation, splitting and text formatting). `reconcile` compares staging with the ledger's canonical hashes, and
  recomputes written hashes from Trailkeep and compares them with the ledger's written hashes. Both, plus the title
  checksum from BUILD_PLAN §15.1 (keyed), are aggregated per entity in canonical-key order.
- **People:** match rate per role; ambiguous matches; fallbacks used.
- **Attachments:** count and total bytes per scan status; files over the importer cap; SHA-256 of each file compared
  with the source when the source supplies one. In `external` mode, the **number and total size of attachments that
  will be stored `NOT_SCANNED`**, as a WARNING the sign-off must acknowledge (§4.3).
- **Forecasts:** open cases by expected SLA state on go-live day; alerts the go-live policy suppresses (§7.1); cases
  the retention job will archive at its next run; cases past purge and what `retention.pastPurge` does with them.
- **Verdict:** `RECONCILED` only when every source row is loaded, skipped by an explicit rule or listed as an issue,
  and both checksum comparisons match. WP-26's acceptance ("synthetic extract reconciles 100 %; re-run is a no-op") is
  this verdict on the synthetic dataset, plus a second `load` reporting zero rows loaded.

## 10. Security and privacy

- **Least privilege at the source:** the grants recipe of §4.1 is the control; the read-only transaction and the query
  validator are defence in depth. The security tests prove the recipe on each vendor.
- **Encrypted in transit:** TLS with certificate and host verification for every JDBC source (§4.1); no
  `trustServerCertificate=true`, no `sslmode=require` without verification.
- **No SQL injection surface:** queries come only from the reviewed mapping file, are parsed and restricted (§4.1),
  are never wrapped or rewritten, and take values only as bind parameters; identifiers are never built from data.
  They run through one `SourceQueryJdbcDao` that accepts only a validated-query type (ADR-0014, Part B amendment).
- **Placeholders cannot exfiltrate.** `${…}` is resolved **only** in `source.url` and `source.user`, and **only** from
  names under `import.source.*`; it is never resolved against the rest of the Spring `Environment`, which includes the
  `configtree` Secret files. A `${` anywhere else (`const`, `lookups`, queries, paths) fails `validate-mapping`. A
  security test maps `title: { const: "${spring.datasource.password}" }` and expects rejection. `validate-mapping` also
  fails on anything that looks like a credential in the file.
- **Untrusted content stays untrusted:** text is normalised and escaped or sanitized as in §6.4; attachments go
  through the intake; every file path stays inside its root (§4.3).
- **PHI minimization:**
  - staging and the content area are readable only by `TRAILKEEP_IMPORT` (§5), purged after sign-off and by the TTL;
  - issues carry codes and numeric details, never values. Driver and parser exceptions carry values (for example
    PostgreSQL's `Key (x)=(…)`, truncation errors, CSV errors that quote the row), so the importer maps each exception
    to an issue code by type and SQLState and **never stores or logs its message**; a test feeds such exceptions
    through and asserts no value reaches `IMPORT_ISSUE` or the log;
  - reports carry counts and keyed hashes; logs carry run ids, entity names and canonical-key HMACs only;
  - `IMPORT_LEDGER` keeps keyed hashes forever, never plain ones (§5).
- **Separation of duties:** the run request and the sign-off are made by two different signed-in users (§3). The Job's
  pod has its own NetworkPolicy allowing egress only to the source database (extract and `validate-mapping` only), the
  Trailkeep database, and, in `clamd` mode, the clamd TLS sidecar (ADR-0012); `load` runs need no source egress. The
  Job uses `TRAILKEEP_IMPORT`, which the web pods never mount.
- **Secrets:** the source password, the `TRAILKEEP_IMPORT` password and `import.hash-key` are Secret files.

## 11. Testing

- **Synthetic legacy schema** (`src/test/resources/importer/legacy-schema/`): one small tracker shape with deliberate
  awkwardness: zone-less timestamps across a DST change, Windows-1252 text, `U+0000` and lone surrogates, a status no
  one documented, orphan comments, a 40 MB attachment (above the 25 MB app cap, so both the over-cap issue and a
  raised importer cap are tested), an EICAR file assembled at runtime, a disguised executable, duplicate people with
  different email case, a composite-key entity, a case with a legacy ACL, a closed case past purge and one under legal
  hold, and Windows backslash paths. DDL and seed data for **SQL Server, Oracle and PostgreSQL**, plus the same data
  exported as CSV and JSON Lines with each vendor's recipe.
- **Recipe tests:** every export command in `docs/importer/export-recipes.md` runs against the vendor container and
  its output is extracted, so a recipe that corrupts data fails the build.
- **Grants recipe tests:** the source-account recipe is applied to each container, and the import account is shown
  unable to insert, update, delete, execute a function, lock rows, or use a link or linked server.
- **Source equivalence:** for each vendor, JDBC extract and file extract of the same data produce identical canonical
  row hashes (SQL Server `DATETIME` compared at its precision). SQL Server runs via Testcontainers
  (`mcr.microsoft.com/mssql/server`, Developer edition, EULA accepted through the container's documented variable for
  testing only), Oracle via the existing `-Poracle-it` images, PostgreSQL as today.
- **Transform and normalisation unit tests:** every transform and every `onMissing`/`onOverflow` branch; key
  canonicalisation; Markdown escaping; property tests that transforms are deterministic.
- **End to end:** extract → dry-run → sign-off → load → reconcile gives `RECONCILED`; a second load is a no-op; a load
  killed mid-run resumes to the same final state; an extract killed mid-entity restarts that entity; a delta run loads
  new keys including child-only ones and reports changed, closed-in-source and missing ones; a delta under a changed
  mapping is refused without the flag.
- **Security tests:** a mapping with two statements, DML, `FOR UPDATE`, a locking hint, `OPENROWSET`, a database link,
  a non-allow-listed function, a `${…}` outside the source block, or a path escaping a root is rejected; imported XSS
  payloads (the existing corpus) render inert; exceptions carrying values never reach issues or logs; the web app's
  credential cannot read staging; restricted imported cases are invisible to people outside the mapped visibility.
- **Part G placement (proposed row):** SQL Server source ITs run on `main`, nightly and on release tags, not on fork
  PRs, like Oracle, because the image is large. PostgreSQL and Oracle importer ITs follow the existing rows. The PR
  that adds them records the row under Plan deviations (IMPLEMENTATION_PLAN is not edited, CLAUDE.md rule 11).

## 12. Delivery plan

Replaces the single WP-26 row. Each item is one PR of at most 400 net production lines, with at most one versioned
migration shipped with its entity or DAO. **Migration versions are the next free version when each PR is opened**, so
they always sort above whatever WP-17, WP-21, WP-24 or others merged first, and Flyway never meets an out-of-order
version.

| PR | Module | Content |
|---|---|---|
| 26a | `importer` | `IMPORT_RUN`, `IMPORT_ISSUE` with entities; `TRAILKEEP_IMPORT` user, `${import_user}` grants, `DatabaseGrantsIT`; `status` command |
| 26b | `importer` | Mapping JSON Schema, YAML parser, `validate-mapping` structure checks: placeholders, paths, lengths, keys |
| 26c | `user` | Import principal and its ADR; read-only person lookups by external id and email |
| 26d | `casefile` | `LEGACY_NUMBER`, `OWNER_NAME_SNAPSHOT` on `CASE_RECORD`; map `LEGACY_REF`; case page shows the legacy number |
| 26e | `casefile` | `CaseImportService`: imported case, SLA row, search build with legacy number, the relaxations of §8.1 with tests |
| 26f | `casefile` | `CaseImportService.importComment`; history comment builder |
| 26g | `document` | `UPLOADER_NAME_SNAPSHOT`; `AttachmentImportService` over the intake (importer cap, hash re-check) |
| 26h | `closure` | Imported closure marker column; `ClosureImportService` (after WP-19) |
| 26i | `importer` | Closed transform set and text normalisation, with unit and property tests |
| 26j | `importer` | Staging tables and entities, checkpoints, TTL purge; JSON Lines file source; manifest |
| 26k | `importer` | CSV/TSV source, encodings, attachment folder and content area |
| 26l | `importer` | JDBC core: `SourceQueryJdbcDao`, query validator, test fetch; PostgreSQL adapter |
| 26m | `importer` | Oracle adapter (SCN, NLS, LOBs) |
| 26n | `importer`, `web` | Import runs screen: run request, sign-off with acknowledgements, audit; axe-clean |
| 26o | `importer` | Dry run: validation rules, issues, preview, forecasts (SLA, people, visibility, `NOT_SCANNED`) |
| 26p | `importer` | `IMPORT_LEDGER`; load of cases with closures, run lock, resume |
| 26q | `importer` | Load of comments and attachments (scanning mode, `NOT_SCANNED` acknowledgement enforced) |
| 26r | `importer` | Delta: child-only loads, `CHANGED_AFTER_IMPORT`, `CLOSED_IN_SOURCE`, `MISSING_IN_SOURCE`, mapping hash |
| 26s | `importer` | SQL Server adapter: `mssql-jdbc`, `testcontainers-mssqlserver`, Part G row |
| 26t | `importer`, `shared` | Reconciliation report (CSV + HTML), shared CSV writer, `reconcile` |
| 26u | `importer`, deploy | `purge-staging`; Job manifest with `TRAILKEEP_IMPORT` Secret, content-area volume, `emptyDir` sizing, NetworkPolicy |
| 26v | `importer`, `sla` | Go-live alert pre-seeding and its ADR (after WP-21) |
| 26w | `importer` | Retention forecast and `retention.pastPurge`, legal hold carry-over (after WP-24) |
| 26x | docs | Export recipes and their tests, source-account recipes and grants tests, the worked example end to end |

**WP-26 dependencies** become WP-11, 13, 14, **19** (closure record), **21** (alert ledger, 26v only) and **24**
(retention rule, 26w only). WP-25 keeps its dependency on WP-26 for "last import run" and links to the Import runs
screen from 26n. If an item turns out larger than 400 lines, it splits again and the PR says so.

## 13. Open questions for the owner

1. **Legacy history:** is the single "Imported history" comment per case enough for v1 (§7.5), or is a filterable
   structured history needed at go-live?
2. **Unmatched owners of open cases:** the design now uses an explicit eligible default owner **per team**
   (`defaults.owner.byTeam`, §7.3), because the match rate before go-live will be low. Is that acceptable, or should a
   later "re-link snapshot to user" command (v1.1) update owners once people have signed in?
3. **Delta conflicts** (`CHANGED_AFTER_IMPORT`): report-only (proposed), or overwrite when the Trailkeep copy has no
   edits since import? This now includes **legacy closures during the freeze** (`CLOSED_IN_SOURCE`, §3.1):
   **Proposed; owner to confirm:** report on a worklist and close through Trailkeep's closure flow, rather than
   closing automatically.
4. **Import actor** (§7.3). **Proposed; owner to confirm:** a seeded non-login import principal, with its own ADR,
   rather than the authenticated approver's `APP_USER` id.
5. **Database credential for staging** (§5). **Proposed; owner to confirm:** a separate `TRAILKEEP_IMPORT` user, so
   the web pods cannot read staged PHI. This amends BUILD_PLAN §8 rule 7.
6. **Go-live alert policy** (§7.1). **Proposed; owner to confirm:** pre-seed `ALERT_LEDGER` for imported open cases,
   with an ADR in WP-21's wake; the alternative is a dated `app.alerts.quiet-until`.
7. **Cases already past purge** (§7.1). **Proposed; owner to confirm:** the mapping must choose; `skip` is proposed;
   legal-hold cases always import.
8. **Sign-off** (§3). **Proposed; owner to confirm:** an authenticated Import runs screen (Compliance Admin approves,
   approver differs from requester), delivered in WP-26 and linked from the WP-25 System screen; the alternative is to
   record sign-off outside Trailkeep and say so.

## 14. Revision history

- **2026-11-05, initial version** (#56).
- **2026-11-05, revised after the independent review on #56.** Findings and where they are addressed:

| Finding | Section |
|---|---|
| B1 attachment bytes at load | §2, §4.3, §5; ADR-0014 rule 1 |
| B2 load through UI services | §2.1, §2.2, §8.1, §12 (26c to 26h) |
| B3 missing columns, actor, closure | §2.2, §7.1, §7.3, §12, §13 q4 |
| B4 read-only overstated | §4.1, §10, §11 |
| B5 scanning of imports | §4.3, §9; ADR-0014 owner-accepted risk |
| S1 SQL Server recipe | §4.2.2 |
| S2 Oracle recipe | §4.2.2 |
| S3 PostgreSQL `bytea` | §4.2.2, §6.3 |
| S4 consistent exports | §4.2, §4.2.1 |
| S5 snapshot cost, resume | §4.1 |
| S6 time zones, keys | §4.1, §5, §6.1 |
| S7 operator SQL vs Part B | §5, §10; ADR-0014 Part B amendment |
| S8 dependencies | §2.1; ADR-0014 "Dependencies" |
| S9 placeholder exfiltration | §6.1, §10, §11 |
| S10 PHI gaps | §5, §9, §10, §13 q5 |
| S11 sign-off | §3, §12 (26n), §13 q8 |
| S12 people matching | §7.3, §9 |
| S13 alerts and retention | §7.1, §9, §13 q6 and q7 |
| S14 delta semantics | §3.1, §13 q3 |
| S15 reconciliation checks | §4.1, §9 |
| S16 raw exports | §1, §4.2, §6.1 |
| S17 attachment limits | §4.3, §11 |
| S18 text hygiene | §6.4, §7.2 |
| S19 migration numbering, Part G | §5, §11, §12 |
| S20 delivery plan | §12 |
| S21 legacy ACLs | §1, §6.1, §7.1, §9 |
| N1 §5.1 not §5.2 | §5; ADR-0014 header |
| N2 rejected option, pros and cons | §7.1; ADR-0014 "Considered options" |
| N3 TLS details | §4.1 |
| N4 DST volume | §4.1, §6.1 |
| N5 UTF-16 without BOM | §4.2 |
| N6 NetworkPolicy to clamd | §10 |
| N7 lookup file containment | §4.3, §6.1 |
| N8 ledger updatable columns | §5 |
| N9 merge order | Header; ADR-0014 "More information" |
