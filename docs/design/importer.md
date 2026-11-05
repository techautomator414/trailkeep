<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Importer design: legacy compliance case history into Trailkeep

- **Status:** Proposed design for WP-26 (decision recorded in
  [ADR-0014](../../adr/0014-importer-sources-and-canonical-model.md)), revised after the independent review and the
  re-review on #56, with the owner's decisions of 2026-11-05
- **Builds on:** BUILD_PLAN.md §5, §6, §8, §9, §15, §16; IMPLEMENTATION_PLAN.md A.4 #33, #34, #37, Part B, Part G,
  WP-16, WP-19, WP-21, WP-24, WP-26; ADR-0004, ADR-0005, ADR-0007, ADR-0012, ADR-0013
- **Merge order:** ADR-0013 (scanning mode, `NOT_SCANNED`, `V4_4__attachment_not_scanned`) has merged (#57), and this
  branch has taken `main` since.
- **Scope:** one-time migrations and delta loads during a cut-over window, from a legacy tracker held in **Microsoft
  SQL Server, Oracle or PostgreSQL**, or from **files exported** from any of them

The organization-specific parts stay outside this repository (BUILD_PLAN §15.4): the mapping file, source
connection details, extracts and reconciliation outputs live in the deploying organization's private configuration
repository. This repository ships the engine, the canonical model, the schemas, the export recipes and a fully
synthetic worked example.

Decisions marked **Decided (owner, 2026-11-05)** were made by the owner on that date; §13 lists them. No decision in
this document is still awaiting the owner.

## 1. Goals and non-goals

**Goals**

1. Import cases, comments, attachments, closure outcomes and (optionally) legacy change history (§7.5) with **zero
   silent loss**: every source row is loaded, deliberately skipped by a mapping rule, or reported as an issue.
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
  provider; unmatched people become name snapshots (§7.3). The two non-login import principals (§7.3) are not people.
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
                                 ▲          run-scoped)  ────► │ (import  │    CLOSURE_REQUEST, CASE_IMPORTED_EVENT,
                      mapping file (YAML, private)             │   APIs)  │──► AUDIT_EVENT, IMPORT_LEDGER
                                                               └────┬─────┘
                                                                    ▼
                                                            Reconciliation report
```

Three stages, each independently re-runnable, separated by tables rather than by memory:

1. **Extract**: a *source adapter* reads the legacy data in one consistent snapshot, applies the mapping's field
   transforms, and writes **canonical staging rows**. It reads each attachment's bytes **once, inside the snapshot**,
   and records their size and SHA-256 (§4.3). This is the only stage that opens a source connection. The load
   re-reads mounted attachment files only to verify their hash (§4.3), so the attachment folder must stay mounted,
   unchanged, until the load has finished. Extract never writes domain tables.
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
  `package-info.java` exists). It depends on public interfaces of `casefile`, `document`, `closure`, `user`, `audit`,
  `sla` (26ac) and `retention` (26ad). Spring Modulith verifies that it reaches nothing else. Owner eligibility
  (`security.CasePermissionService.mayOwn`) is checked through `casefile`'s import API, so `importer` does not depend
  on `security`. **Those interfaces do not exist yet**; §2.2 lists them and §12 delivers them.
- It runs as a **Kubernetes Job** (`deploy/jobs/importer-job.yaml` exists) with the `importer` profile
  (`web-application-type=none`, no OIDC, no web schedulers), the same image as the web app (BUILD_PLAN §16). The
  mapping file and any extract files arrive on a read-only volume; the run's content area is a separate writable
  volume (§4.3). The source credential and the `TRAILKEEP_IMPORT` credential arrive as Secret files (Spring
  `configtree`), never as environment text. Today the Job mounts the web app's Secret; §12 (26ab) changes that.
- **Two persistence units' worth of entities in one image (R3).** Hibernate runs `ddl-auto: validate`
  (`application.yml`), and on Oracle `DatabaseMetaData` reads the `ALL_*` views, which list only objects the
  connecting user holds a privilege on. The web app, connecting as `TRAILKEEP_APP`, has no grant on staging, so it
  would report the staging tables missing and fail to start. The staging entities (`IMPORT_STG_*`,
  `IMPORT_RUN_CHECKPOINT`) therefore live in `io.trailkeep.importer.staging`, and the managed types are filtered by
  profile: the web profiles exclude that package, and the `importer` profile includes it. The other way round,
  `TRAILKEEP_IMPORT` gets `SELECT` on every table any entity maps (§5), so validation sees them all. Both directions
  are tested on Oracle (26a for the filter and the non-staging import tables, 26j for staging); PostgreSQL's driver
  reads `pg_catalog` and would not catch this, which is why the Oracle test is the one that counts.
- Because there is one image, every new runtime dependency (ADR-0014, "Dependencies") is on the web app's classpath
  and in its SBOM too, and is scanned there. Only the importer profile calls it.
- Commands: `validate-mapping`, `extract`, `dry-run`, `load`, `reconcile`, `status`, `purge-staging` and
  `relink-owners` (§7.3). Each is one Job run with `--import.command=…` and `--import.run=<id>`; the run id comes from
  the authenticated run request (§3).

### 2.2 What exists today and what WP-26 adds

Checked against `main` at 3fe138e (#57 merged).

| Need | On `main` today | Added by (§12) |
|---|---|---|
| Create a case with original dates, closed status, `LEGACY_REF` | `CaseService.create(Actor, CaseFields)` opens at `clock.instant()`, always `OPEN`; `LEGACY_REF` column exists but is not mapped on `CaseRecord` | `CaseImportService` in `casefile` (26e) |
| Show the legacy number, snapshot an unmatched owner | No column; `CaseSearchWriter.rebuild` uses title, description and comments only | `LEGACY_NUMBER`, `OWNER_NAME_SNAPSHOT` on `CASE_RECORD`; `rebuild` includes the legacy number (26d) |
| Post a comment with original time and author snapshot | `CASE_COMMENT.AUTHOR_NAME_SNAPSHOT` and nullable `AUTHOR_ID` exist; `CaseCommentService.post` uses the actor and the clock | `CaseImportService.importComment` (26f) |
| Store an attachment from the importer | `UploadIntake` is package-private and `receive(String, InputStream)` always copies into `temp-dir`; no public attachment service yet | `AttachmentImportService` in `document` with a `Path` entry point, `UPLOADER_NAME_SNAPSHOT` (26g) |
| Record a closure outcome | `CASE_RECORD` has no outcome; outcome lives on `CLOSURE_REQUEST` (`V5__closure`, WP-19, not built) | `ClosureImportService` in `closure` after WP-19 (26h) |
| Actors for `CREATED_BY`, `UPLOADED_BY`, `REQUESTED_BY`, `APPROVER_ID`, `DECIDED_BY` (FKs to `APP_USER`) | No system user row | Two import principals in `user`, with their ADR (26c) |
| Match legacy people | `UserDirectoryService` has no lookup by email or external id | Read-only lookups in `user` (26c) |
| `NOT_SCANNED` status | `V4_4__attachment_not_scanned` (#57, ADR-0013) | Exists |
| Legacy change history on the History tab | No table; the History tab is WP-16, not built | `CASE_IMPORTED_EVENT` in `audit` (26v, 26w); History tab display after WP-16 (26x) |
| Place legal hold | `LEGAL_HOLD*` columns in `V4__case`, not mapped on `CaseRecord`; mapped by WP-24 | `retention` import API after WP-24 (26ad) |
| Audit with origin `IMPORT` | `AuditOrigin.IMPORT`, `CK_AUDIT_ORIGIN`; `ACTOR_ID` nullable, no FK | Exists |
| Unrestricted scope for non-request work | `CaseScope.system()` | Exists |

## 3. Lifecycle of an import

| Step | Who / command | What happens | Writes domain? |
|---|---|---|---|
| 0 | **Run request** on the Import runs screen | A Compliance Admin or System Admin opens a run (`IMPORT_RUN`, `REQUESTED`), naming the source id; audited | No |
| 1 | `validate-mapping` | Mapping parsed against its JSON Schema; lookups resolved; paths contained; placeholders checked (§10); each query validated (§4.1) and **test-fetched with `setMaxRows(1)`**; files and manifest checked | No |
| 2 | `extract` | Source read in one snapshot (§4.1); transforms applied; staging and content area filled, with a keyed row hash per row | No |
| 3 | `dry-run` | All rules checked; `IMPORT_ISSUE` filled; preview and forecasts produced (§9) and their aggregates written to `IMPORT_RUN_SUMMARY`; the scanning mode in force recorded on `IMPORT_RUN.SCAN_MODE` | No |
| 4 | **Sign-off** on the Import runs screen | A Compliance Admin (the requester may approve their own run) reviews the run summary on the screen, including the recorded scanning mode, acknowledges each WARNING class (including `NOT_SCANNED`, §4.3), and approves; stored as `APPROVED_BY` (an `APP_USER` id) and audited | No |
| 5 | `load` | Loads only rows not already in `IMPORT_LEDGER`, one case per transaction; resumable | Yes |
| 6 | `reconcile` | Final report comparing source, staging and Trailkeep (§9) | No |
| 7 | `purge-staging` | Deletes the run's staging rows and content area (they hold PHI); also enforced by a TTL (§5) | No |

`load` refuses to start unless the run passed `dry-run` with zero fatal issues, is signed off, the mapping hash and
staging have not changed since the sign-off, and **the scanning mode in force equals `IMPORT_RUN.SCAN_MODE`**, the
mode the sign-off approved (R1). The mode is read where the attachment intake reads it, so an `env` override on the
Job or a ConfigMap change between dry run and load is caught, not assumed away. A changed mode means a new dry run
and a new sign-off. Error-severity issues are allowed only below the mapping's `limits.maxErrorRate` and are always
listed in the report.

**Sign-off: in-app, any Compliance Admin (Decided (owner, 2026-11-05)).** The importer profile has no OIDC, so a name
typed into a Job argument proves nothing. The run request and the sign-off are therefore made by signed-in users on an
authenticated **Import runs** screen (BUILD_PLAN §3.2: "import runs" is visible to Compliance and System Admins; only
Compliance Admins may approve), stored as `APPROVED_BY`, an `APP_USER` id, with an audit event. The owner decided that
**there is no separate approver**: any Compliance Admin may approve, including the one who requested the run, so
there is no `APPROVED_BY <> REQUESTED_BY` check and no two-person rule. The sign-off is a check against mistakes (a
wrong mapping, a missed warning), not a control against a malicious operator (§10). What the approver sees is the
**run summary** (R8): the preview's aggregates (counts per entity and issue code, forecasts, match rates, the
visibility report, the `NOT_SCANNED` count and size, the past-purge and legal-hold counts, the recorded scanning mode)
are persisted in `IMPORT_RUN_SUMMARY`, which `TRAILKEEP_APP` can read, and the screen renders them; no field values
are stored there. The CSV and HTML reports on the Job's run volume (26ab) carry the same figures and the per-row
detail, and reach the operator out of band. WP-25 (System screen) depends on WP-26 for "last import run", so the
Import runs screen ships inside WP-26 (26s) and the System screen links to it once WP-25 lands; there is no cycle.

### 3.1 Delta runs

A **delta run** (cut-over freeze) is a new run with a new `extract` against the same source and mapping:

- **New keys** load normally. That includes **child-only loads**: a new comment or attachment whose parent case is
  already in `IMPORT_LEDGER` attaches to the existing Trailkeep case found through the ledger, in one transaction per
  parent case with its ledger rows, search append and audit events.
- **Same key, same canonical hash**: skipped.
- **Same key, different canonical hash**: reported as `CHANGED_AFTER_IMPORT` and **never overwritten** (Trailkeep may
  hold edits). A case that was open at first load and is **closed in the source** now gets the sub-code
  `CLOSED_IN_SOURCE` and goes on a **closure worklist** on the run's page (Trailkeep case numbers, linked); its owner
  closes it through the normal closure flow, not automatically, because an automatic closure would bypass the
  approval rule (BUILD_PLAN §3.2). **Decided (owner, 2026-11-05): report plus a closure worklist.** New comments and
  attachments on existing cases still load (above).
- **Ledger keys absent from the new extract**: reported as `MISSING_IN_SOURCE` (WARNING). Nothing is deleted.
- **Mapping changes**: every ledger row records the mapping hash it was loaded under. A delta under a different
  mapping hash is refused unless the run sets `--import.allow-mapping-change`; with it, row-hash change detection is
  switched off for that run (any field change would otherwise flag every row) and the report says so. New and missing
  keys are still handled as above.
- **`CLOSED_IN_SOURCE` is always detected (R10).** It does not use the row hash: each case ledger row also records the
  canonical status and closed-at time as loaded, and every delta compares the staged status and closed-at with them
  directly. That comparison runs under `--import.allow-mapping-change` and after an `import.hash-key` rotation (§5)
  too, so legacy closures during the freeze, the most common cut-over change, are never hidden by a lookup fix.

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

**Read-only: the account's grants are the control for tables; the validator's allow-list is the control for
function calls.** The recipes grant:

- `SELECT` on the named tables or views the mapping reads, and nothing else: no `EXECUTE` granted to the account, no
  `db_datareader` on everything, no `SELECT ANY TABLE`;
- no database links (Oracle), no linked servers (SQL Server), no `dblink`/`postgres_fdw` and no
  `pg_execute_server_program` or `pg_write_server_files` (PostgreSQL);
- on Oracle, `FLASHBACK` on the same objects only when `AS OF SCN` is used.

Grants cannot remove what every account already has (R4), and the recipes say so per vendor:

| | What `PUBLIC` already holds | What the recipe does | So the control for function calls is |
|---|---|---|---|
| PostgreSQL | `EXECUTE` on every function, including the legacy schema's, unless revoked | `REVOKE EXECUTE ON ALL FUNCTIONS IN SCHEMA <legacy> FROM PUBLIC` where the DBA can, always on a restored copy; built-in functions stay executable | The validator's allow-list; the `READ ONLY` transaction still refuses DML, including DML inside a function |
| Oracle | `EXECUTE` on many `DBMS_*` and `UTL_*` packages | Revokes nothing on a production source (DBAs rarely can); recommends a restored copy | The validator's allow-list; the read-only transaction refuses DML except in an autonomous-transaction function, which only a function call can reach |
| SQL Server | Membership of `public`, with `EXECUTE` on some system objects; user functions only if granted to `public` | Checks that no legacy-schema object is granted to `public`, or recommends a restored copy | The validator's allow-list and the grants; SQL Server has **no read-only transaction** |

Defence in depth, never relied on alone:

- The connection is opened read-only and every query runs in the snapshot transaction above, with a statement
  timeout. On PostgreSQL and Oracle the transaction refuses DML. **SQL Server has no read-only transaction**:
  `setReadOnly(true)` does not make mssql-jdbc refuse writes, and `ApplicationIntent=ReadOnly` only routes to a
  secondary. On Oracle, a function with `PRAGMA AUTONOMOUS_TRANSACTION` runs in its own transaction, outside the
  read-only one, which is why function calls are restricted by the validator below.
- Each mapping query is **parsed** (JSqlParser, Apache-2.0 election; ADR-0014) and must be exactly one `SELECT` or
  `WITH … SELECT`. The validator rejects statement separators, `INTO`, `FOR UPDATE` and locking hints (`UPDLOCK`,
  `XLOCK`, `HOLDLOCK`, `TABLOCKX`), `OPENROWSET`/`OPENQUERY`/`OPENDATASOURCE`, Oracle `@dblink` references, and any
  function call not on a per-vendor allow-list of pure built-ins (string, date, numeric, `COALESCE`, `CAST`, JSON
  construction for exports). A query the parser cannot read is rejected, not passed.
- `validate-mapping` **test-fetches one row** (`setMaxRows(1)`, statement timeout) instead of asking for a plan:
  Oracle's `EXPLAIN PLAN` writes to `PLAN_TABLE`, and SQL Server has no `EXPLAIN` (`SHOWPLAN` needs its own
  permission).
- The security tests run the grants recipe itself on each vendor's container and assert what the recipe actually
  achieves (§11): the import account cannot insert, update, delete or reach a link; on PostgreSQL it cannot execute a
  legacy-schema function after the recipe's revoke (and the validator rejects the call when the revoke was not
  possible); on Oracle and SQL Server the validator rejects calls outside the allow-list. Each vendor's recipe and IT
  ship with that vendor's adapter (26n, 26o, 26p).

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
  values). Each row is one JSON object on one line, because JSON escapes line breaks. Whether `-y 0` still truncates
  a value at 1 MB is **not confirmed**: the recipe fixture includes a description over 1 MB, so the recipe test
  settles it, and if it truncates, the recipe uses the JDBC source for long text. If CSV is required, the recipe
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
- **PostgreSQL.** One script, `psql -X -v ON_ERROR_STOP=1 -f export.sql`, so every `\copy` runs inside the one
  transaction of §4.2.1 (a separate `psql -c` per file would not):

  ```sql
  BEGIN ISOLATION LEVEL REPEATABLE READ READ ONLY;
  \copy (<case query>)    TO 'cases.csv'    WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
  \copy (<comment query>) TO 'comments.csv' WITH (FORMAT csv, HEADER, ENCODING 'UTF8')
  COMMIT;
  ```

  `bytea` is exported as hex inside the CSV (`\x…`, the default `bytea_output`), which the CSV adapter decodes when
  the mapping declares the column `binary: hex`, or read through JDBC. `lo_export`/`\lo_export` apply only to real
  `oid` large objects, never to `bytea`.

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
`CONTENT_CHANGED` issue that rolls back that case's transaction; the folder must therefore stay mounted and unchanged
from extract until the load has finished. Either way, the bytes the dry run validated are the
bytes that are loaded, and the load never opens a source connection. The content area holds PHI: the volume must be
encrypted at rest (a platform responsibility, as in BUILD_PLAN §9 "Data at rest"), and the content area is deleted by
`purge-staging` and by the staging TTL (§5).

**Paths.** Legacy paths are often Windows UNC or backslash paths. The mapping may declare `attachments.stripPrefix`
(removed literally, case-insensitively); `\` is normalised to `/`; then the path is resolved under the root, and
`toRealPath()` must start with the root's real path. Absolute paths, `..` segments and symlinks that leave the root
are rejected (`PATH_OUTSIDE_ROOT`). The same rule applies to every file the mapping names, including lookup CSVs (§6.1).

**Size.** `app.attachments.max-bytes` defaults to 25 MB (`AttachmentProperties`), and legacy scans are often larger.
The importer has its own cap, `import.attachments.max-bytes` (default: the app's cap). clamd's `StreamMaxLength` is a
clamd server setting that the importer cannot read (`deploy/base/configmap.yaml` only mentions it in a comment), so
in `clamd` mode the deployment **declares** it as `import.attachments.clamd-stream-max` (R13; required in `clamd`
mode, set to the clamd server's value), and startup fails if the importer cap exceeds it. A file over the importer cap
is not loaded and becomes an `ATTACHMENT_TOO_LARGE` ERROR issue carrying its size, found at dry run, before any scan.
If the declaration is wrong and clamd still answers with its size-limit error, 26g maps that one reply (`INSTREAM
size limit exceeded`) to a distinct `ScanSizeExceededException`, a subtype of `ScannerUnavailableException`, so
browser uploads still fail closed exactly as today while the import records `ATTACHMENT_TOO_LARGE` for that file and
continues, instead of stopping the whole load as an outage on every resume. With the default `maxErrorRate`, an
`ATTACHMENT_TOO_LARGE` issue blocks the load until the operator raises the cap or **accepts the skip explicitly** (N-c):
the mapping lists the code in `limits.acceptSkip: [ATTACHMENT_TOO_LARGE]`, which turns those issues into "skipped by
rule" (still counted and listed) and adds them to the WARNING classes the sign-off must acknowledge, with their count
and total size. Files
above the app's cap but within the importer's are stored and downloadable; the download path streams chunks and does
not depend on the size. On PostgreSQL the driver holds a whole `bytea` value in memory, so the content query fetches
one value at a time and the Job's memory limit bounds the importer cap there; the recipe says so and prefers path
sources for large files.

**Intake and scanning.** Every attachment goes through `document`'s intake (`UploadIntake`), exactly like a browser
upload: size cap, detected type against the allow-list, extension cross-check, refusal of disguised executables, image
header cap, SHA-256, and the deployment's scanning mode (ADR-0012, ADR-0013). A file that fails the type checks is not
loaded and becomes an issue. In `clamd` mode an infected file is stored `QUARANTINED` and reported (BUILD_PLAN §15.2),
and a scanner outage stops the load cleanly with the run resumable. The import entry point (§8.1) scans the content-area
or mounted file directly and re-checks its SHA-256 while writing chunks. Today the only entry point,
`UploadIntake.receive(String, InputStream)`, always copies into `temp-dir`, so 26g adds a `Path` entry point that runs
the same checks on a file in place (N-b). Attachments therefore need no copies on the Job's `emptyDir`; the run volume
is sized for the run's source-query attachments instead (26ab).

**`external` scanning mode: imported attachments follow the app's mode (owner decision, 2026-11-05).** In `external`
mode imported attachments are stored `NOT_SCANNED`, exactly like uploads, and are downloadable (ADR-0013). Legacy files
never pass the upstream scanner on their way in, so this is the risk ADR-0013 names; ADR-0014 records it as an
**owner-accepted risk**. To make it visible before anything is stored, the dry run reports the **number and total size
of attachments that will be stored `NOT_SCANNED`** as a WARNING that the sign-off must acknowledge (§3, §9). The
acknowledgement is **bound to a mode (R1)**: the dry run records the mode in force on `IMPORT_RUN.SCAN_MODE`, the
sign-off approves that mode, and `load` refuses to start when the mode in force differs. The importer normally reads the
mode from the same ConfigMap as the web app (`APP_ATTACHMENTS_SCANNING`), but the operator who applies the Job can
override it in the Job's `env`, and the ConfigMap can change between two Job runs, so the design checks rather than
assumes. Each imported attachment's audit entry records the mode in force (`ScannedUpload.scanMode()`, ADR-0013). The
Job manifest gets an `external` variant without the `clamav-ca` volume, mirroring `deploy/overlays/external-scanning`
(26ab). There is no always-scan rule for imports.

## 5. Canonical staging model

Vendor-neutral tables in the Trailkeep database, one per canonical entity, keyed by `(RUN_ID, LEGACY_KEY)`. Each
table ships with the entity that uses it, in the PR that first writes it (§12); staging rows are JPA entities
(`Length.LONG32` for long text, no `@Lob`) in `io.trailkeep.importer.staging`, which only the `importer` profile's
managed types include (§2.1), so the only native SQL is the source query DAO (ADR-0014, Part B amendment).

| Table | Holds | Notes |
|---|---|---|
| `IMPORT_RUN` | Run: kind (`IMPORT` or `RELINK`), source id, requester, approver, status, mapping hash, source fingerprint (snapshot, SCN or manifest hash), scanning mode recorded at dry run (`SCAN_MODE`, R1), counts, acknowledged warning classes, staging expiry | BUILD_PLAN §5.1 "Supporting tables" lists it |
| `IMPORT_RUN_SUMMARY` | Run, metric key, optional dimension (team id, SLA state, scan status, role), numeric value | The preview's and the report's aggregates for the screen (R8); numbers only, never field values |
| `IMPORT_STG_PERSON` | Legacy people: key, display name, email, external id hint | Only to resolve owners and authors |
| `IMPORT_STG_CASE` | Title, description, subject, case type key, team key, owner key, priority, sensitivity, ACL flag, status, identified on, opened at, closed at, closure outcome and summary, legal hold, legacy number | One row per legacy case |
| `IMPORT_STG_COMMENT` | Case key, author key or name, written at, body, body format | Insert-only target |
| `IMPORT_STG_ATTACHMENT` | Case key, file name, size, SHA-256, content pointer (content-area name, path or content id), uploaded at, uploader key | Bytes are in the content area or the mounted folder, never in staging |
| `IMPORT_STG_EVENT` | Optional legacy history: case key, at, who, kind, field, old value, new value | §7.5 |
| `IMPORT_ISSUE` | Run, stage, entity, **keyed HMAC of the canonical key** (not the plain key, N-h), severity (`FATAL`/`ERROR`/`WARNING`), code, numeric detail (for example a size) | Codes, never values (§10) |
| `IMPORT_LEDGER` | `(SOURCE_ID, ENTITY, LEGACY_KEY)` unique → `CASE_ID` (FK), `TARGET_ID`, canonical hash, written hash, mapping hash, canonical status and closed-at as loaded (cases only, R10), run id, loaded at | Idempotency across runs; insert-only |
| `IMPORT_RUN_CHECKPOINT` | Run, stage, entity finished | Restart per entity (§4.1) |
| `IMPORT_OWNER_PENDING` | Case id (FK), team default owner id, keyed HMACs of the legacy owner's normalised email and external id, run id | Open cases given a default owner, for `relink-owners` (§7.3); deleted when relinked |

`IMPORT_LEDGER.CASE_ID` and `IMPORT_OWNER_PENDING.CASE_ID` reference `CASE_RECORD` and are indexed, per the FK policy
(A.4 #35); `TARGET_ID` is the comment, attachment, event or case id and has no FK because it is polymorphic by
`ENTITY`. `IMPORT_RUN.REQUESTED_BY` and `APPROVED_BY` reference `APP_USER`; they may be the same user (§3).

**Issue keys after staging expires (N-h).** `IMPORT_ISSUE` is readable by `TRAILKEEP_APP`, so it never holds a plain
legacy key. While staging exists the importer resolves the HMAC to the staged row for the report file; once staging
has expired or been purged, an issue stays a code, a count and a keyed hash, which only someone holding both the
legacy key and `import.hash-key` can match again.

**Keys.** A canonical key is a list of one or more components (`key: case_id` or `key: [region, case_seq]`, §6.1), each
canonicalised: integral numbers as plain decimal (`BigDecimal.stripTrailingZeros().toPlainString()`, so Oracle `1` and
`1.0` and CSV `"1"` agree and `100` stays `100`, not `1E+2`; a non-integral numeric key is an issue); GUIDs as
lower-case hyphenated; strings exactly as read, unless the entity declares `keyCase: insensitive` (for SQL Server
case-insensitive collations), in which case they are lower-cased (`Locale.ROOT`). Components are joined with `/`, with
`/` and `\` escaped. The engine sorts staging by canonical key itself, so no source ordering or collation matters.

**Length budget.** `LEGACY_REF VARCHAR(100)` holds `<source id>:<canonical key>`. `source.id` is at most 16
characters (`[a-z0-9-]`), and `validate-mapping` plus the extract reject any key that would make `LEGACY_REF` exceed
100 characters (`KEY_TOO_LONG`). `LEGACY_REF` is the identity; the number people know is `LEGACY_NUMBER` (§7.1).

**Hashes are keyed.** Row hashes, the reconciliation checksums and the `hash` transform use **HMAC-SHA-256 with a
server-side key** (`import.hash-key`, a Secret file of at least 32 bytes, the same rule as `AuditHmac`), never plain
SHA-256: titles and names are short and a plain hash is dictionary-reversible (BUILD_PLAN §9, "Audit"). It is a
separate key from the audit key. **The Job holds both keys (R2)**: import audit events and imported history redact
descriptions, subjects and bodies with `SnapshotDiff`'s `AuditHmac` (§7.4, §7.5), and `AuditConfig` refuses to start
in `prod` without `app.audit.hmac-key`, so the Job (profiles `prod,importer`) mounts `app.audit.hmac-key` and
`import.hash-key` as two separate Secret files, never one value used for both (§10, 26ab). Rotating `import.hash-key`
makes every delta row look changed, so a rotation is run with `--import.allow-mapping-change` semantics (§3.1);
`CLOSED_IN_SOURCE` is still detected. Attachment SHA-256 values are
plain, as in `CASE_ATTACHMENT.SHA256`: they hash whole files, not short text.

**Grants: a separate `TRAILKEEP_IMPORT` login (Decided (owner, 2026-11-05)).** Staging in the Trailkeep schema
would otherwise be readable by `TRAILKEEP_APP`, so every web pod could read every staged restricted description
outside `CaseScope`. The importer Job connects as a fourth database user, `TRAILKEEP_IMPORT` (`${import_user}` in
`R__grants.sql`, `APP_DB_IMPORT_USER` in the ConfigMap; it must exist like `TRAILKEEP_PURGE`, and a deployment that
never imports leaves it locked):

| Object | `TRAILKEEP_IMPORT` | `TRAILKEEP_APP` |
|---|---|---|
| `IMPORT_STG_*`, `IMPORT_RUN_CHECKPOINT` | S I D | none |
| `IMPORT_ISSUE` | S I D | S (codes, counts and keyed hashes, for the screen) |
| `IMPORT_RUN_SUMMARY` | S I D | S |
| `IMPORT_RUN` | S I, U of status, counts, fingerprint, `SCAN_MODE` and expiry columns | S I (run request), U of `APPROVED_BY`, `APPROVED_AT` and the acknowledgement columns |
| `IMPORT_LEDGER` | S I (no U, no D) | S |
| `IMPORT_OWNER_PENDING` | S I D | S (counts for the screen) |
| `CASE_IMPORTED_EVENT` (§7.5) | S I (no U, no D) | S (History tab) |
| `ALERT_LEDGER` (26ac, N-d) | S I (no U, no D) | Unchanged |
| Domain tables the load writes | The same insert grants as `TRAILKEEP_APP` on `CASE_RECORD` (plus U for `OWNER_ID`, `LEGAL_HOLD*`, `UPDATED_*`, `VERSION`, for `relink-owners` and the hold), `CASE_SLA`, `CASE_SEARCH` (plus U for the append), `CASE_COMMENT`, `CASE_ATTACHMENT`, `CASE_ATTACHMENT_CHUNK`, `CLOSURE_REQUEST`, `AUDIT_EVENT`; sequence `USAGE` | Unchanged |
| Every other table an entity maps (`APP_USER`, `TEAM`, `APP_USER_TEAM`, configuration, `CASE_COMMENT_PIN`, …) | S only, so `validate` sees every mapped table on Oracle (R3) | Unchanged |

The "insert-only" rules carry over unchanged: `TRAILKEEP_IMPORT` has no UPDATE or DELETE on `CASE_COMMENT`,
`CASE_ATTACHMENT_CHUNK`, `AUDIT_EVENT`, `ALERT_LEDGER`, `CASE_IMPORTED_EVENT` or `IMPORT_LEDGER`.
`TRAILKEEP_PURGE` gets S D on `CASE_IMPORTED_EVENT`, because imported history is case content that purge removes
with comments (§7.5). `AbstractDatabaseGrantsIT` and its PostgreSQL and Oracle subclasses gain the new column. This
changes BUILD_PLAN §8 rule 7 (three users become four, and "importer staging tables" move from the app user to the
import user), so the PR records it under Plan deviations.

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
  acceptSkip: [ATTACHMENT_TOO_LARGE] # optional: these ERROR codes become "skipped by rule", acknowledged at sign-off
  batchSize: 500
lookups:                           # value tables, inline or from a private CSV under the mapping directory
  status:   { "Open": OPEN, "In Review": OPEN, "Closed": CLOSED, "Void": CLOSED }
  priority: { "1": HIGH, "2": NORMAL, "3": NORMAL }
  outcome:  { "Substantiated": SUBSTANTIATED, "Unfounded": UNSUBSTANTIATED }
  team:     { file: lookups/teams.csv, key: legacy_unit, value: trailkeep_team }
  historyField: { "State": status, "Assignee": owner, "Details": description, "Subject": subject }
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
  event:                           # optional legacy change history (§7.5)
    query: SELECT audit_id, case_id, changed_at, changed_by, field_name, old_val, new_val FROM dbo.case_audit
    key: audit_id
    parent: { entity: case, columns: [case_id] }
    fields:
      at:       { timestamp: changed_at }
      whoKey:   changed_by
      kind:     { const: FIELD_CHANGE }
      field:    { lookup: historyField, from: field_name, onMissing: default, default: other }
      oldValue: old_val                # redacted unless field is on the plain allow-list (§7.5)
      newValue: new_val
attachments:
  root: /var/lib/trailkeep/import/files
  stripPrefix: '\\fileshare\cases\'            # synthetic; removed before resolving under root
people:
  query: SELECT user_id, display_name, email FROM dbo.users
  key: user_id
  match: [ externalId, email ]                 # in order; an ambiguous email match is an issue, not a guess
  unmatched: snapshot
defaults:
  owner: { byTeam: lookups/default-owners.csv } # eligible owner per team for open cases; relink-owners later (§7.3)
visibility:
  onUnrepresentableAcl: restricted             # restricted | standard | fail
# No retention block: cases past purge or on legal hold are always imported and held (§7.1).
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

`text` formats then apply, and they differ by target (R9). **Comments** (`CASE_COMMENT.BODY`) hold Markdown that is
rendered and sanitized on display (`V4_1__comment.sql`, `MarkdownRenderer`). **The description is plain text**: the
case page renders it with `th:text` (`casefile/case.html`), never through Markdown. So:

| Format | Written into a comment (imported comments, the `continueInComment` overflow) | Written into the description |
|---|---|---|
| `plain` | **Markdown-escaped** (`\` before `*`, `_`, `` ` ``, `#`, `[`, `]`, `<`, `>`, `\|`, and before a leading `1.`, `-` or `+`), so legacy text such as `1.` or `*` reads as typed | Stored normalised, **not escaped**; `th:text` already shows it as typed |
| `markdown` | Stored as is; the existing sanitizer makes HTML inert on display | Stored as is; shown as literal text (the description has no Markdown rendering) |
| `html` | Reduced to text with the existing OWASP sanitizer and an empty policy, then Markdown-escaped | Reduced to text the same way, **not escaped** |

Nothing is ever stored as markup. The overflow of a description continued in a comment is escaped, because from then
on it is a comment.

## 7. Mapping onto the Trailkeep model

### 7.1 Cases

- **Numbering.** Imported cases get **new Trailkeep numbers** from `SEQ_CASE_NUMBER` in the one
  `PREFIX-YEAR-SEQUENCE` scheme. The year is the **load** year, as for any case created that day, so a 2019 case
  becomes `PREFIX-2026-…`; its original dates stay on the record. Keeping legacy numbers was considered and rejected
  (ADR-0014, "Considered options"): it collides with the format and with numbers issued after go-live.
- **Legacy number.** `CASE_RECORD` gains `LEGACY_NUMBER VARCHAR(100)` (26d), shown on the case page as "Legacy
  number"; `LEGACY_REF` stays the internal identity and is not shown. The legacy number is part of the `CASE_SEARCH`
  text when the search row is built, and stays there: today `CaseSearchWriter.rebuild` rebuilds the text from title,
  description and comments only, so the first edit of an imported case would drop it. 26d changes `rebuild` to
  include `LEGACY_NUMBER` when present, with a test that edits an imported case and still finds it by legacy number
  (R5).
- **Status.** `OPEN` and `CLOSED` only. A legacy "pending approval" maps to `OPEN`; the closure flow restarts in
  Trailkeep. `ARCHIVED` is never imported.
- **Closed cases** import as `CLOSED` with their real `CLOSED_AT`, `CASE_SLA.SLA_STATE = NOT_APPLICABLE` and null due
  dates (BUILD_PLAN §6). `CASE_RECORD` has no outcome column: the outcome lives on `CLOSURE_REQUEST` (BUILD_PLAN
  Appendix A, `V5__closure`, WP-19). An imported closure is therefore stored as one `APPROVED` `CLOSURE_REQUEST`
  written by `closure`'s import API (26h): outcome from the mapping (validated against the case type's outcomes),
  resolution summary from the mapping or "(no resolution summary in the legacy record)", `REQUESTED_AT` and
  `DECIDED_AT` from the legacy closure time, `REQUESTED_BY` the **"Trailkeep import"** principal, and
  `APPROVER_ID`/`DECIDED_BY` the **"Legacy approval (imported)"** principal (§7.3, R7). No current person is recorded
  as deciding a closure made years before: the run's approver goes only into the audit details (§7.4). This satisfies
  `CK_CLOSURE_SOD` (`REQUESTED_BY <> APPROVER_ID`) without weakening it, and BUILD_PLAN §3.2's owner-≠-approver rule
  holds too, because no case is ever owned by the legacy-approval principal. A marker column added by 26h labels the
  request "Imported closure" so nobody mistakes it for a Trailkeep approval, and the case page shows the approver as
  "Legacy approval (imported)". WP-26 therefore depends on WP-19.
- **Open cases** get their SLA from the case type's policy computed from the **original** dates with the one anchor
  rule (ADR-0007), and their state as of load time, so the first aging report is honest. The dry run forecasts how
  many will be At Risk or Overdue on day one, so management can be briefed (BUILD_PLAN §15.2).
- **Go-live alerts: pre-mark as alerted (Decided (owner, 2026-11-05)).** On its first run the SLA evaluator would
  send At Risk and Overdue mail for every imported open case. The load therefore **pre-seeds `ALERT_LEDGER`** for the
  current period of each imported open case, with the alerts its load-time state would trigger in the current cycle,
  so the evaluator sends no wave; later transitions and overdue repeats alert normally. Cases still show their true
  SLA state, and the dry run reports how many cases are pre-marked, per alert type. This needs WP-21's ledger and gets
  its own ADR in that PR (26ac).
- **Retention: import and hold all (Decided (owner, 2026-11-05)).** The retention job (WP-24) archives closed cases
  older than the case type's `RETENTION_YEARS`, and purge then deletes their content, so imported cases already
  **past purge** would be deleted right after the load. The behaviour is fixed, with no mapping choice: **every case
  past its purge date** (by WP-24's rule, computed at load) **and every case on legal hold in the legacy system**
  (`legalHold: true` in the mapping) is imported and **placed on hold**, so nothing is deleted until someone reviews
  it. The hold uses the `LEGAL_HOLD` columns of `V4__case`, which WP-24 maps, through `retention`'s import API (26ad):
  `LEGAL_HOLD = true`, `LEGAL_HOLD_BY` the "Trailkeep import" principal, `LEGAL_HOLD_AT` the load time, and a fixed
  `LEGAL_HOLD_REASON`: "Imported: on legal hold in the legacy system (import run N)" or "Imported: past its purge date
  at import; review before release (import run N)". Releasing it is the normal WP-24 legal-hold release by a
  Compliance Admin, audited; after release the retention rules apply as to any case. The hold is written in the
  case's load transaction, so no imported case is ever unheld for a moment. Until 26ad has merged, the dry run
  reports any past-purge or held case as a FATAL `HOLD_UNAVAILABLE`, so such cases cannot load without their hold.
  The dry-run forecast reports how many cases will be held for each reason and how many the retention job will
  archive at its next run. This item lives in 26ad only, after WP-24 (R12).
- **Inactive case types.** Closed cases may use an inactive case type, as legacy closed history often does. Open
  cases need an active one; otherwise the case is an issue and the mapping remaps it.
- **Visibility.** Team and sensitivity are mapped explicitly; an unmapped team is a fatal issue unless `onMissing:
  default` names a team. Trailkeep's model cannot express legacy per-user access lists: a `RESTRICTED` case is still
  visible to its owner, the team's Managers and Compliance Admins, and a `STANDARD` case to the whole team. So
  instead of promising that access never widens, the dry run **reports the mapped visibility per case** (team,
  sensitivity, how many cases had a legacy ACL), and `visibility.onUnrepresentableAcl` decides cases whose
  `legacyAcl` is true: `restricted` (default), `standard` or `fail`.
- **Search.** `CASE_SEARCH` is built once per case from title, description, legacy number and all imported comments,
  through `casefile`'s search writer, inside the case's transaction; later rebuilds keep the legacy number (R5).

### 7.2 Comments

Imported through `casefile`'s import API (`importComment`, 26f) with their **original timestamp** and an **author
snapshot** in `AUTHOR_NAME_SNAPSHOT` (`AUTHOR_ID` null when the author has no Trailkeep account). Bodies are
normalised and formatted as in §6.4. The 20 000-character cap (`CaseComment.MAX_BODY`) applies: longer bodies are split
into numbered parts with an issue, or fail, as the mapping declares.

### 7.3 People and the import actors

People are **never created** by the importer. `people.match` resolves a legacy person to a Trailkeep user by external
id or email, through read-only lookups added to `user` (26c).

- **Expect a low match rate.** `APP_USER` rows are created at first sign-in (`UserSignInService`), and before go-live
  most staff have never signed in. The dry run reports the **match rate** per role (owners of open cases, owners of
  closed cases, authors, uploaders). The runbook recommends a sign-in drive before the final load.
- **Email is not unique** in `APP_USER`. Matching is case-insensitive, and an email that matches more than one user
  is an `AMBIGUOUS_PERSON` issue, never a guess.
- **Eligibility.** A matched owner of an open case must be able to own cases in the mapped team (Analyst or Manager,
  `security.CasePermissionService.mayOwn`, called by `casefile`'s import API, N-e) and not be disabled; otherwise the
  case falls back as if unmatched.
- **Unmatched authors and uploaders** become name snapshots (`AUTHOR_NAME_SNAPSHOT`; `UPLOADER_NAME_SNAPSHOT`, added
  by 26g; `UPLOADED_BY` is the "Trailkeep import" principal).
- **Unmatched owners of open cases: a default owner plus a later re-link (Decided (owner, 2026-11-05)).** The case
  gets the team's default owner from `defaults.owner.byTeam`, who must be eligible; the dry run fails if a team with
  open cases has none. The legacy owner's name goes to `OWNER_NAME_SNAPSHOT`, and the load writes an
  `IMPORT_OWNER_PENDING` row holding keyed HMACs of the legacy owner's normalised email and external id (no plain
  values, so nothing outlives staging in the clear). Later, once people have signed in, a Compliance Admin requests a
  `RELINK` run on the Import runs screen and the operator runs `relink-owners` (26z). It hashes the email and
  external id of each enabled `APP_USER` with the same key, matches the pending rows, and reassigns a case to the
  matched person only when the case is still `OPEN`, is still owned by the default owner (a person who reassigned it
  meanwhile wins), and the person is eligible to own in the case's team. Each reassignment goes through
  `CaseImportService.relinkOwner` in one transaction with an `ORIGIN = IMPORT` audit event (`CASE_OWNER_RELINKED`,
  old and new owner ids, the run id) and deletes the pending row. Ambiguous and ineligible matches are reported and
  left alone. It runs as a dry run by default and changes owners only with `--import.apply=true`; the counts go to
  `IMPORT_RUN_SUMMARY`. Closed cases are not relinked; they keep the snapshot. Pending rows are keyed with
  `import.hash-key`, so relinking is done before that key is rotated.
- **Unmatched owners of closed cases**: `OWNER_ID` is the "Trailkeep import" principal and `OWNER_NAME_SNAPSHOT` holds
  the legacy name, shown on the case page. The principal can never act on a case, so a reopen must reassign first;
  WP-19's reopen therefore has to check owner eligibility (a note for WP-19).

**The import actors: two seeded non-login principals (Decided (owner, 2026-11-05)).** `CASE_RECORD.CREATED_BY`,
`UPDATED_BY` and `OWNER_ID`, `CASE_ATTACHMENT.UPLOADED_BY` and `CLOSURE_REQUEST.REQUESTED_BY`, `APPROVER_ID` and
`DECIDED_BY` are foreign keys to `APP_USER`, and there is no system user. With R7's refinement, 26c seeds **two**
principals, each an `APP_USER` row with a reserved external id no identity provider issues, `DISABLED` set (sign-in
already refuses disabled users, `UserSignInServiceImpl`), no team or global roles, and therefore no access to
anything:

| Principal | Display name | Used as |
|---|---|---|
| Import | "Trailkeep import" | `CREATED_BY`, `UPDATED_BY`, owner of unmatched closed cases, `UPLOADED_BY`, the imported closure's `REQUESTED_BY`, `LEGAL_HOLD_BY` of import holds, actor of import audit events |
| Legacy approval | "Legacy approval (imported)" | `APPROVER_ID` and `DECIDED_BY` of every imported closure, and nothing else |

Two principals satisfy `CK_CLOSURE_SOD` without naming a real person as the decider of a historical closure; the
run's approver is recorded in the audit details (§7.4). Because they are users that do not come from the identity
provider, they need their own ADR, written with 26c, which also decides how they are seeded portably (migration or an
idempotent `user` service call). The rejected option, the run's approver as `CREATED_BY` and decider, would make a
real person the owner of every unmatched closed case, including restricted ones, and the recorded decider of closures
made years before.

### 7.4 Audit

Every created case, comment, attachment and closure writes one `AUDIT_EVENT` with `ORIGIN = IMPORT`, the import
principal as actor (`ACTOR_NAME_SNAPSHOT` "Trailkeep import"), the run id and approver id in the details, and the **load
time** as its timestamp. Descriptions, subjects and comment bodies are redacted as for any event (`@Redacted`, HMAC
with the audit key, which the Job therefore holds, §5).
The original legacy timestamps live on the imported rows themselves (`OPENED_AT`, `CLOSED_AT`, comment `CREATED_AT`,
`UPLOADED_AT`). The audit trail therefore stays true: it records when Trailkeep learned each fact, not a back-dated
claim. The run request and sign-off are audited by the screen (§3).

### 7.5 Legacy history: a structured history table (Decided (owner, 2026-11-05))

If the legacy system kept a change history, the mapping fills `IMPORT_STG_EVENT` (the `event` entity, §6.1), and the
load writes each event as a row of a new **insert-only** table, `CASE_IMPORTED_EVENT`, in the `audit` module. It is a
separate table from `AUDIT_EVENT`, so the audit trail stays a record of what Trailkeep itself did, and the History tab
shows both.

| Column | Holds |
|---|---|
| `ID` | Sequence `SEQ_CASE_IMPORTED_EVENT` |
| `CASE_ID` | FK to `CASE_RECORD`, indexed with `AT_TS` (`IX_CASE_IMPORTED_EVENT_CASE (CASE_ID, AT_TS)`) |
| `AT_TS` | The legacy event time, in the declared zone (§4.1) |
| `WHO_NAME_SNAPSHOT` | The legacy actor's name as recorded, `VARCHAR(200)`; no user id, because legacy actors are mostly unmatched |
| `KIND` | `FIELD_CHANGE`, `STATUS_CHANGE`, `ASSIGNMENT`, `NOTE` or `OTHER` (`CHECK`) |
| `FIELD_NAME` | The canonical field (`status`, `owner`, `priority`, `team`, `due`, `title`, `description`, `subject`, `other`), `VARCHAR(40)` |
| `OLD_VALUE`, `NEW_VALUE` | Plain text up to 1000 characters (normalised as in §6.4, truncated with an issue), **or** null when redacted |
| `OLD_HMAC`, `NEW_HMAC`, `OLD_LENGTH`, `NEW_LENGTH` | For redacted fields: HMAC-SHA-256 with the audit key and the length, exactly as `SnapshotDiff` stores `@Redacted` audit values |
| `RUN_ID` | The import run |

**Redaction (PHI).** Old and new values are stored **HMAC-redacted like audit, never as text**, for `description`,
`subject`, `NOTE` events and every field the mapping does not map onto the plain allow-list. Only `status`, `owner`,
`priority`, `team`, `due` and `title` are stored as text; `title` is plain in `AUDIT_EVENT` too. An unknown legacy
field therefore defaults to redacted, so a mapping mistake hides a value rather than exposing one. The History tab
shows a redacted change as "changed" with no value, as it does for audit.

**Writing and reading.** `audit` gets an import-only write API, `ImportedHistoryService` (called only by `importer`,
ArchUnit), used in the case's load transaction with its ledger rows (`ENTITY = event`). The read path is a
package-private `@CaseRooted` repository, `CaseImportedEventRepository`, whose every method takes a `CaseScope`,
injected only into the `@CaseAccessPath` class that serves the History tab, so an unreadable case answers 404 as
everywhere else. The History tab (BUILD_PLAN §4.3: "chronological, filterable … with who/when/origin") merges the rows
with the case's audit events by time, marks each one **"Imported"** with origin `IMPORT`, and adds an "Imported
history" filter. Purge (WP-24) deletes a case's imported events with its comments, as case content
(`TRAILKEEP_PURGE` S D, §5).

**Dependency on WP-16.** The History tab is WP-16, which has not landed (the case page says the tabs arrive with
WP-14 to WP-19). The table, entity, write API and load step (26v, 26w) do not need it. The read path and the display
(26x) depend on WP-16: if WP-16 has merged when 26x is opened, 26x extends its tab; if not, 26x waits for it. WP-16
does not depend on WP-26, so there is no cycle.

## 8. Load: correctness under failure

### 8.1 Import-only APIs

The load cannot use the UI services: `CaseService.create` takes a real user's `Actor`, opens at the current instant,
always creates `OPEN`, rejects inactive case types and owners who are not Analyst or Manager in the team, and has no
`LEGACY_REF`, closure or outcome; `CaseCommentService.post` stamps the actor's name and the current time; and
`UploadIntake` is package-private. WP-26 therefore adds import-only APIs, each in its own module, each called only by
`importer` (ArchUnit rule):

| API | Module | Does |
|---|---|---|
| `CaseImportService` | `casefile` | Create an imported case with original dates, `OPEN` or `CLOSED`, `LEGACY_REF`, `LEGACY_NUMBER`, owner snapshot; check owner eligibility (`mayOwn`); its `CASE_SLA` row; add an imported comment with timestamp and author snapshot; build the search text once; `relinkOwner` (§7.3) |
| `AttachmentImportService` | `document` | Run the intake on a content-area or mounted file (`Path` entry point) with the importer cap, verify the expected SHA-256 while writing chunks, store with uploader snapshot |
| `ClosureImportService` | `closure` | Write the `APPROVED` imported closure request (§7.1) |
| `ImportedHistoryService` | `audit` | Insert `CASE_IMPORTED_EVENT` rows with redaction (§7.5) |
| Import hold | `retention` (WP-24) | Place the import legal hold (§7.1), after WP-24 (26ad) |
| Alert pre-seeding | `sla` (WP-21) | Insert the current period's `ALERT_LEDGER` rows (§7.1), after WP-21 (26ac) |
| Import principals, person lookups | `user` | §7.3 |

**Rules they keep:** `CaseScope` (they run under `CaseScope.system()`, which exists for non-request work, and never on
a request thread); the redaction policy for audit; the same validation of sizes, enums, the type allow-list and
scanning as uploads; insert-only comments and chunks; the SLA anchor rule; one transaction per case with its audit.

**Rules they relax, deliberately and only for imports (R6).** This is the complete list; each has a test named
after it, and an ArchUnit rule keeps every method that applies one callable only from `importer`:

| # | Relaxation | API | Test |
|---|---|---|---|
| 1 | The case opens at the legacy `OPENED_AT`, not the clock | `CaseImportService` | `importedCaseKeepsLegacyOpenedAt` |
| 2 | The case may be created `CLOSED`, with the legacy `CLOSED_AT` | `CaseImportService` | `importedCaseMayBeCreatedClosed` |
| 3 | A closed case may use an inactive case type | `CaseImportService` | `closedImportMayUseInactiveType` |
| 4 | A closed case's owner may be the "Trailkeep import" principal, who cannot own cases | `CaseImportService` | `closedImportMayBeOwnedByPrincipal` |
| 5 | An open case's owner may be the team's default owner in place of the legacy one; `relinkOwner` later reassigns with no human actor | `CaseImportService` | `defaultOwnerAppliedAndRelinked` |
| 6 | A comment carries a legacy `CREATED_AT` and an author snapshot, not the actor and the clock | `CaseImportService` | `importedCommentKeepsLegacyTimeAndAuthor` |
| 7 | An attachment carries a legacy `UPLOADED_AT`, the principal as `UPLOADED_BY` and an uploader snapshot, and the importer's size cap | `AttachmentImportService` | `importedAttachmentKeepsLegacyTimeAndCap` |
| 8 | A closure request is written already `APPROVED`, with requester and approver principals who did not act, legacy `REQUESTED_AT`/`DECIDED_AT`, and the BUILD_PLAN §3.2 approver-eligibility check skipped (owner ≠ approver still holds, §7.1) | `ClosureImportService` | `importedClosureSkipsApproverEligibilityOnly` |
| 9 | Legal hold is placed by the "Trailkeep import" principal, who holds no Compliance Admin role | `retention` import API | `importHoldPlacedWithoutComplianceAdmin` |
| 10 | `ALERT_LEDGER` rows are written for alerts that were never sent | `sla` import API | `goLiveAlertsPreMarked` |
| 11 | History rows (`CASE_IMPORTED_EVENT`) are written with legacy times and a name snapshot | `ImportedHistoryService` | `importedHistoryKeepsLegacyTimeAndRedacts` |

Nothing outside this table is relaxed.

### 8.2 Transactions and idempotency

- **One case per transaction.** Each case is loaded with its SLA row, closure request, search row, comments,
  attachments, imported history, hold, pre-seeded alert rows, pending-owner row, ledger rows and audit events in
  **one transaction**, so a case is either fully in or absent; a crash
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

Produced by `dry-run` (preview) and `reconcile` (final), as CSV and an accessible HTML page in the run's `reports/`
directory on the Job's run volume (26ab), holding counts and keyed hashes only, never field values. The aggregates
also go to `IMPORT_RUN_SUMMARY`, which the Import runs screen renders for the approver (§3, R8); the files add the
per-row detail and reach the operator out of band. Legacy keys in the CSV are written with the `= + -
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
- **When reconcile runs (N-i).** Once people edit imported cases, written hashes recomputed from Trailkeep diverge
  legitimately. The runbook therefore runs the final `reconcile` **before the cut-over opens** to users. Run later, it
  compares written hashes only for insert-only rows (comments, attachments, imported history, imported closures),
  compares cases by canonical hash only, and says so in the verdict.
- **People:** match rate per role; ambiguous matches; fallbacks used.
- **Attachments:** count and total bytes per scan status; files over the importer cap, and those accepted as skipped
  (`limits.acceptSkip`, §4.3); SHA-256 of each file compared with the source when the source supplies one. The
  scanning mode recorded for the run. In `external` mode, the **number and total size of attachments that will be
  stored `NOT_SCANNED`**, as a WARNING the sign-off must acknowledge (§4.3).
- **History:** imported events per kind, and how many values were redacted (§7.5).
- **Forecasts:** open cases by expected SLA state on go-live day; alerts pre-marked by the go-live policy, per alert
  type (§7.1); cases the retention job will archive at its next run; cases to be held, past purge and legacy legal
  hold counted separately (§7.1); open cases given a default owner, per team (§7.3).
- **Verdict:** `RECONCILED` only when every source row is loaded, skipped by an explicit rule or listed as an issue,
  and both checksum comparisons match. WP-26's acceptance ("synthetic extract reconciles 100 %; re-run is a no-op") is
  this verdict on the synthetic dataset, plus a second `load` reporting zero rows loaded.

## 10. Security and privacy

- **Least privilege at the source:** the grants recipe of §4.1 is the control for tables, and the validator's
  function allow-list is the control for function calls that `PUBLIC` can already make (R4); the read-only
  transaction is defence in depth. The security tests prove what the recipe achieves on each vendor.
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
- **Sign-off is a check against mistakes, not against a malicious operator (N-j).** The run request and the sign-off
  are made by signed-in users, and by owner decision one Compliance Admin may do both (§3). Whoever holds the
  `TRAILKEEP_IMPORT` credential can insert domain rows directly, so the sign-off and the load's refusal to run
  without it stop a wrong mapping or an unread warning, not a person with that credential. That credential is
  therefore held only by the Job's Secret, and its use is visible in the audit and the ledger.
- **Network and credentials:** the Job's pod has its own NetworkPolicy allowing egress only to the source database
  (extract and `validate-mapping` only), the Trailkeep database, and, in `clamd` mode, the clamd TLS sidecar (ADR-0012);
  `load` runs need no source egress. The Job uses `TRAILKEEP_IMPORT`, which the web pods never mount.
- **Secrets:** the source password, the `TRAILKEEP_IMPORT` password, `import.hash-key` and `app.audit.hmac-key` are
  Secret files (R2). The two HMAC keys are separate files with separate values; the importer mounts the audit key
  because `AuditConfig` requires it in `prod` and import audit events and imported history are redacted with it.

## 11. Testing

- **Synthetic legacy schema** (`src/test/resources/importer/legacy-schema/`): one small tracker shape with deliberate
  awkwardness: zone-less timestamps across a DST change, Windows-1252 text, `U+0000` and lone surrogates, a status no
  one documented, orphan comments, a 40 MB attachment (above the 25 MB app cap, so both the over-cap issue and a raised
  importer cap are tested), an EICAR file assembled at runtime, a disguised executable, duplicate people with different
  email case, a composite-key entity, a case with a legacy ACL, a closed case past purge and one under legal hold, a
  legacy change history with description and status changes, an integral key of `100`, a description over 1 MB (to
  settle the `sqlcmd -y 0` question, §4.2.2), and Windows backslash paths. DDL and seed data for **SQL Server, Oracle
  and PostgreSQL**, plus the same data exported as CSV and JSON Lines with each vendor's recipe.
- **Recipe tests:** every export command in `docs/importer/export-recipes.md` runs against the vendor container and
  its output is extracted, so a recipe that corrupts data fails the build.
- **Grants recipe tests,** shipped with each adapter (26n, 26o, 26p): the source-account recipe is applied to each
  container, and the import account is shown unable to insert, update, delete, lock rows, or use a link or linked
  server. Function calls are asserted as §4.1 states per vendor: on PostgreSQL the account cannot execute a
  legacy-schema function after the recipe's revoke; on every vendor the validator rejects a non-allow-listed call.
- **Persistence units on Oracle (R3):** the web profile starts with `validate` as `TRAILKEEP_APP` with staging
  tables present, and the importer profile starts as `TRAILKEEP_IMPORT` (26a, 26j).
- **Source equivalence:** for each vendor, JDBC extract and file extract of the same data produce identical canonical
  row hashes (SQL Server `DATETIME` compared at its precision). SQL Server runs via Testcontainers
  (`mcr.microsoft.com/mssql/server`, Developer edition, EULA accepted through the container's documented variable for
  testing only), Oracle via the existing `-Poracle-it` images, PostgreSQL as today.
- **Transform and normalisation unit tests:** every transform and every `onMissing`/`onOverflow` branch; key
  canonicalisation (including `100` staying `100`); Markdown escaping applied to comments and never to descriptions;
  history-field redaction defaulting to redacted; property tests that transforms are deterministic.
- **End to end:** extract → dry-run → sign-off → load → reconcile gives `RECONCILED`; a second load is a no-op; a load
  killed mid-run resumes to the same final state; an extract killed mid-entity restarts that entity; a delta run loads
  new keys including child-only ones and reports changed, closed-in-source and missing ones; a delta under a changed
  mapping is refused without the flag, and with the flag still reports `CLOSED_IN_SOURCE`; a load whose scanning mode
  differs from the recorded one is refused; the past-purge and legacy-held cases arrive held with the fixed reasons;
  `relink-owners` reassigns a case after its owner signs in and leaves a manually reassigned one alone; an edited
  imported case is still found by its legacy number.
- **Security tests:** a mapping with two statements, DML, `FOR UPDATE`, a locking hint, `OPENROWSET`, a database link,
  a non-allow-listed function, a `${…}` outside the source block, or a path escaping a root is rejected; imported XSS
  payloads (the existing corpus) render inert; exceptions carrying values never reach issues or logs; the web app's
  credential cannot read staging; restricted imported cases are invisible to people outside the mapped visibility,
  and so is their imported history (404); redacted history values never appear as text.
- **Part G placement (proposed row):** SQL Server source ITs run on `main`, nightly and on release tags, not on fork
  PRs, like Oracle, because the image is large. PostgreSQL and Oracle importer ITs follow the existing rows. The PR
  that adds them records the row under Plan deviations (IMPLEMENTATION_PLAN is not edited, CLAUDE.md rule 11).

## 12. Delivery plan

Replaces the single WP-26 row. Each item is one PR of at most 400 net production lines, with at most one versioned
migration shipped with its entity or DAO. **Migration versions are the next free version when each PR is opened**, so
they always sort above whatever WP-16, WP-17, WP-21, WP-24 or others merged first, and Flyway never meets an
out-of-order version. Letters after 26z continue as 26aa to 26ae. R11's splits are 26j/26k, 26m/26n and 26q/26r.

| PR | Module | Content | Depends on |
|---|---|---|---|
| 26a | `importer` | `IMPORT_RUN` (with `SCAN_MODE`, kind), `IMPORT_ISSUE` (keyed key), `IMPORT_RUN_SUMMARY` with entities; `TRAILKEEP_IMPORT` user, `${import_user}` grants incl. `SELECT` on every mapped table, grants ITs; profile-filtered managed types with the Oracle `validate` IT; `status` command | WP-11 |
| 26b | `importer` | Mapping JSON Schema, YAML parser, `validate-mapping` structure checks: placeholders, paths, lengths, keys, `acceptSkip` | 26a |
| 26c | `user` | The two import principals and their ADR; read-only person lookups by external id and email | WP-11 |
| 26d | `casefile` | `LEGACY_NUMBER`, `OWNER_NAME_SNAPSHOT` on `CASE_RECORD`; map `LEGACY_REF`; case page shows the legacy number; `CaseSearchWriter.rebuild` keeps it, with the edit test (R5) | WP-11 |
| 26e | `casefile` | `CaseImportService`: imported case, SLA row, owner eligibility, search build with legacy number, relaxations 1 to 5 of §8.1 with tests (`relinkOwner` itself in 26z; no legal hold, R12) | 26c, 26d |
| 26f | `casefile` | `CaseImportService.importComment` with relaxation 6; description overflow comment (escaped) | 26e, WP-13 |
| 26g | `document` | `UPLOADER_NAME_SNAPSHOT`; `AttachmentImportService` with a `Path` intake entry point, importer cap, hash re-check; `ScanSizeExceededException` for clamd's size-limit reply (R13) | 26c, WP-14 |
| 26h | `closure` | Imported closure marker column; `ClosureImportService` with the two principals (relaxation 8) | 26c, WP-19 |
| 26i | `importer` | Closed transform set and text normalisation, comment-only escaping (R9), with unit and property tests | 26b |
| 26j | `importer` | Staging tables and entities in `importer.staging`, checkpoints, TTL purge; web-profile exclusion proven on Oracle | 26a |
| 26k | `importer` | JSON Lines file source; manifest | 26i, 26j |
| 26l | `importer` | CSV/TSV source, encodings, attachment folder and content area | 26k |
| 26m | `importer` | Query validator: JSqlParser, `ValidatedSourceQuery`, per-vendor reject list and function allow-lists | 26b |
| 26n | `importer` | `SourceQueryJdbcDao`, test fetch; PostgreSQL adapter (snapshot, zones, `bytea`); PostgreSQL grants recipe and IT | 26j, 26m |
| 26o | `importer` | Oracle adapter (SCN, NLS, LOBs); Oracle grants recipe and IT | 26n |
| 26p | `importer` | SQL Server adapter: `mssql-jdbc`, `testcontainers-mssqlserver`, Part G row; SQL Server grants recipe and IT | 26n |
| 26q | `importer` | Dry run: validation rules, issues, recorded scanning mode | 26e, 26k |
| 26r | `importer` | Dry-run forecasts and `IMPORT_RUN_SUMMARY` writer: SLA, people, visibility, `NOT_SCANNED`, holds, default owners | 26q |
| 26s | `importer`, `web` | Import runs screen: run request, sign-off by any Compliance Admin with acknowledgements and the recorded mode, run summary rendering, audit; axe-clean | 26r |
| 26t | `importer` | `IMPORT_LEDGER`; load of cases with closures, run lock, resume | 26h, 26s |
| 26u | `importer` | Load of comments and attachments; scanning-mode check against `SCAN_MODE` (R1); `clamd-stream-max`; `acceptSkip` | 26f, 26g, 26t |
| 26v | `audit` | `CASE_IMPORTED_EVENT` table, entity, `ImportedHistoryService` with redaction, grants (import S I, app S, purge S D) | WP-11 |
| 26w | `importer` | Load of staged events into `CASE_IMPORTED_EVENT`; history-field mapping and plain allow-list | 26t, 26v |
| 26x | `audit`, `web` | `CaseImportedEventRepository` (`@CaseRooted`) and the History tab's imported rows, marker and filter; axe-clean | 26v, WP-16 |
| 26y | `importer` | Delta: child-only loads, `CHANGED_AFTER_IMPORT`, status-only `CLOSED_IN_SOURCE` (R10), `MISSING_IN_SOURCE`, mapping hash; closure worklist on the run page | 26u |
| 26z | `importer`, `casefile` | `IMPORT_OWNER_PENDING`; `relink-owners` command, `RELINK` runs, `CaseImportService.relinkOwner` with audit | 26t |
| 26aa | `importer`, `shared` | Reconciliation report (CSV + HTML), shared CSV writer, `reconcile` with the N-i rule | 26u |
| 26ab | `importer`, deploy | `purge-staging`; Job manifest with the `TRAILKEEP_IMPORT`, `import.hash-key` and `app.audit.hmac-key` Secrets, the run volume (content area and `reports/`), NetworkPolicy, `external` variant | 26aa |
| 26ac | `importer`, `sla` | Go-live alert pre-seeding and its ADR | 26t, WP-21 |
| 26ad | `importer`, `retention` | Import hold for past-purge and legacy-held cases, its forecast, `HOLD_UNAVAILABLE` lifted | 26t, WP-24 |
| 26ae | docs | Export recipes and their tests, the worked example end to end | 26l, 26o, 26p, 26aa |

That is **31 PRs**. Each "Depends on" points only at earlier rows or at other work packages, so there is no cycle
inside WP-26. **WP-26 dependencies** become WP-11, 13, 14, **16** (History tab, 26x only), **19** (closure record,
26h), **21** (alert ledger, 26ac only) and **24** (legal hold and retention rule, 26ad only). None of WP-16 (WP-06,
11, 14), WP-19 (WP-12, 17), WP-21 (WP-11, 17) or WP-24 (WP-14, 19) depends on WP-26, and WP-25 keeps its dependency
on WP-26 for "last import run" and links to the Import runs screen from 26s, so there is no cycle between work
packages either. The largest items (26n, 26r, 26s, 26u) are the ones most likely to need another split; if any turns
out larger than 400 lines, it splits again and the PR says so.

## 13. Owner decisions

All open questions were decided by the owner on 2026-11-05. No question is left open.

| # | Question | Decision | Where |
|---|---|---|---|
| 1 | Import actor | Two seeded non-login principals, "Trailkeep import" and "Legacy approval (imported)"; own ADR in 26c | §7.3, §7.1 |
| 2 | Staging credential | A separate `TRAILKEEP_IMPORT` login; amends BUILD_PLAN §8 rule 7 | §5 |
| 3 | Go-live alerts | Pre-mark as alerted: `ALERT_LEDGER` pre-seeded for the current period; true SLA state shown; counts in the dry run | §7.1 |
| 4 | Cases past purge | Import and hold all, and hold legacy legal holds too; fixed behaviour, no mapping choice | §7.1 |
| 5 | Sign-off | In-app, any Compliance Admin, the requester included; no separate approver | §3, §10 |
| 6 | Legacy change history | A structured insert-only `CASE_IMPORTED_EVENT` table on the History tab, PHI values HMAC-redacted | §7.5 |
| 7 | Unmatched open-case owners | A default owner per team, the legacy name kept, and a later `relink-owners` | §7.3 |
| 8 | Delta changes | Report as `CHANGED_AFTER_IMPORT`, never overwrite; `CLOSED_IN_SOURCE` on a closure worklist | §3.1 |
| — | Scanning of imports in `external` mode (decided earlier) | Follow the app's mode, an owner-accepted risk, bound to the recorded mode | §4.3 |

## 14. Revision history

- **2026-11-05, initial version** (#56).
- **2026-11-05, revised after the independent review on #56.** Findings and where they are addressed (section
  numbers, PR letters and §13 question numbers as they were at 68ea8c6):

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

- **2026-11-05, follow-ups from the re-review on #56 and the owner's decisions.** The branch took `main` (#57
  merged). Items and where they are addressed:

| Item | Resolution | Section |
|---|---|---|
| R1 scan mode not bound to the load | `IMPORT_RUN.SCAN_MODE` recorded at dry run and approved; `load` refuses a different mode; "cannot differ" dropped | §3, §4.3, §5, §12 (26q, 26u) |
| R2 importer holds the audit key | The Job mounts both keys as separate Secrets | §5, §7.4, §10, §12 (26ab) |
| R3 `validate` vs grants on Oracle | Staging entities filtered out of the web profile's managed types; `TRAILKEEP_IMPORT` gets `SELECT` on every mapped table; Oracle ITs | §2.1, §5, §11, §12 (26a, 26j) |
| R4 `PUBLIC` execute | Per-vendor table; PostgreSQL revoke where possible; validator allow-list is the control for function calls; ITs assert what the recipe achieves | §4.1, §10, §11 |
| R5 legacy number lost on rebuild | `CaseSearchWriter.rebuild` includes `LEGACY_NUMBER`, with an edit test | §2.2, §7.1, §12 (26d) |
| R6 incomplete relaxation list | Complete numbered table of eleven relaxations, each with a test | §8.1; ADR-0014 rule 6 |
| R7 approver as historical decider | Two principals; the run's approver only in audit details | §7.1, §7.3 |
| R8 what the approver sees | `IMPORT_RUN_SUMMARY` rendered on the screen; reports on the defined run volume | §3, §5, §9, §12 (26r, 26s, 26ab) |
| R9 description escaping | Escaping applies to comments only; descriptions stored unescaped | §6.4 |
| R10 flag hides `CLOSED_IN_SOURCE` | Status-only comparison on ledger-recorded status and closed-at, always on | §3.1, §5 |
| R11 oversized PRs, grants ITs last | 26j/26k, 26m/26n, 26q/26r split; each grants recipe ships with its adapter | §12 |
| R12 legal hold in two places | Hold only in 26ad, after WP-24; dropped from 26e and the `CaseImportService` row | §7.1, §8.1, §12 |
| R13 clamd `StreamMaxLength` unknowable | Declared `import.attachments.clamd-stream-max`; size-limit reply becomes a per-file issue | §4.3, §12 (26g, 26u) |
| N-a extract wording | "Only extract opens a source connection"; folder stays mounted until load | §2, §4.3; ADR-0014 rule 1 |
| N-b in-place scan, `emptyDir` | `Path` intake entry point in 26g; `emptyDir` sizing removed | §4.3, §12 |
| N-c accepting a skip | `limits.acceptSkip`, acknowledged at sign-off | §4.3, §6.1 |
| N-d grants alignment | ADR amendment matches §5; `ALERT_LEDGER` insert for `TRAILKEEP_IMPORT` | §5; ADR-0014 |
| N-e `mayOwn` module | Checked through `casefile`'s import API | §2.1, §7.3 |
| N-f `sqlcmd -y 0` 1 MB | Marked unconfirmed; > 1 MB fixture settles it | §4.2.2, §11 |
| N-g `psql` transaction | One `psql -f export.sql` script | §4.2.2 |
| N-h key canonicalisation, issue keys | `toPlainString()`; `IMPORT_ISSUE` holds a keyed hash | §5 |
| N-i reconcile after edits | Run before cut-over opens; insert-only rows only afterwards | §9 |
| N-j sign-off threat model | One line in §10 and ADR-0014; PR body wording fixed | §10; ADR-0014 |
| B2, B3, S20, N9 (partial) | Closed by R6; R5 and R7; R11; the merge of `main` | as above |
| Owner decision 1, import actor | Two non-login principals | §7.3 |
| Owner decision 2, staging credential | `TRAILKEEP_IMPORT` login | §5 |
| Owner decision 3, go-live alerts | Pre-mark as alerted | §7.1 |
| Owner decision 4, past purge | Import and hold all; `retention.pastPurge` removed | §6.1, §7.1, §9 |
| Owner decision 5, sign-off | Any Compliance Admin, the requester included; `CHECK` removed | §3, §10 |
| Owner decision 6, legacy history | `CASE_IMPORTED_EVENT` table, History tab display; moved from v1.1 into WP-26 | §7.5, §12 (26v to 26x) |
| Owner decision 7, open-case owners | Default owner plus `relink-owners` | §5, §7.3, §12 (26z) |
| Owner decision 8, delta changes | Report plus closure worklist | §3.1, §12 (26y) |
