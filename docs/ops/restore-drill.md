<!-- SPDX-License-Identifier: Apache-2.0 -->
# Restore drill (WP-30 part c)

BUILD_PLAN §16 ("Backups/DR") leaves backups to the deploying organization's database administrators, because the
attachments are in the database and one backup covers everything, and asks for a restore drill before 1.0.0. This page
records that drill: what was backed up, with which commands, how long each step took, and what was checked before the
restored copy was called good. The operator's procedure built from it is `docs/RUNBOOK.md`, section 4.

## Targets

BUILD_PLAN states no recovery point or recovery time objective: "backups per organization policy" (§9, data at rest)
and "database owned by the organization's DBAs per policy" (§16). There is nothing to pass or fail against, so the
drill measures, and the numbers below are what a deploying organization sets its own RPO and RTO against. With the
logical backups drilled here, the RPO is the interval between backups; point-in-time recovery (PostgreSQL WAL archiving,
Oracle RMAN with archive logs) is the database administrators' way to shorten it and needs nothing from Trailkeep.

## What is restored

The release volume of `docs/perf/release-500k.md` (the 500k seed: 50 000 cases, 500 000 comments, 500 000 audit
events, the search text), plus:

- **2 000 attachments**, as many files as the importer volume run's legacy tracker (`docs/perf/importer-volume.md`),
  one case in 25 with one. Stored as the application stores them: a `CASE_ATTACHMENT` row and 1 MiB
  `CASE_ATTACHMENT_CHUNK` rows. Most are 4 to 260 KiB, every 50th 1 to 4 MiB (several chunks): 348 MiB in all. The
  bytes are pseudo-random, so they do not compress, as most real attachments (PDF, images, office files) do not.
- **Audit events written by the application**, through `AuditRecorder`, with a redacted value each, under an
  `app.audit.hmac-key` generated for the run (no key is written down).
- The perf IT's Compliance Admin, so the application can be used as a person.

Attachments are a fraction of a real deployment's: a site with 200 KiB a case would hold about 10 GB. Backup and
restore of the attachment bytes are a straight copy (the PostgreSQL restore wrote its 447 MiB file back in 13 s), so
the times below scale with the data; the Oracle Text rebuild scales with the search text, which the 500k seed holds at
full size.

## How it runs

`AbstractRestoreDrillIT`, with `PostgresRestoreDrillIT` and `OracleRestoreDrillIT`, in
`src/test/java/io/trailkeep/restoredrill`. Opt-in, in the `perf-it` profile's `restore-drill` and
`restore-drill-oracle` executions, which no workflow runs:

    ./mvnw -Pperf-it test-compile \
      org.apache.maven.plugins:maven-failsafe-plugin:integration-test@restore-drill \
      org.apache.maven.plugins:maven-failsafe-plugin:verify@restore-drill
    # Oracle Free 23 (add -Dtrailkeep.oracle.image=oracle-xe for XE 21):
    ./mvnw -Pperf-it test-compile \
      org.apache.maven.plugins:maven-failsafe-plugin:integration-test@restore-drill-oracle \
      org.apache.maven.plugins:maven-failsafe-plugin:verify@restore-drill-oracle

The table and the checked facts go to `target/restore-drill/`. Each run:

1. **Source.** Starts a database container (Oracle: three 512 MB redo groups, as `deploy/README.md` asks), migrates an
   empty schema with the migration `Job`'s profile as the schema owner, and applies the seed as the owner. On Oracle the
   owner has exactly `TRAILKEEP_OWNER`'s privileges, and the seed is loaded as the runbook's go-live import is: index on
   manual sync, load, `CTX_DDL.SYNC_INDEX`, back to `SYNC (ON COMMIT)` (below). Then the application starts as the
   runtime user: it writes the audit canaries, runs a search and downloads six attachments.
2. **Fingerprint.** Every table of the schema: its row count and a SHA-256 over every row's every value in primary-key
   order (attachment bytes and audit JSON included). Oracle Text's own `DR$` tables are left out (an import rebuilds
   them; the index is checked by using it). Also the search index's state and every sequence's high-water mark.
3. **Backup**, timed, with the vendor's tool, and the file copied off the container.
4. **Loss.** The container is destroyed.
5. **Restore**, timed, into a new, empty container: the grantees are created (the dump's grants name them), the file
   copied in, the dump restored.
6. **Proof.** On the restored copy:
   - the fingerprint equals the source's, table for table: **row counts and every value match**;
   - the search index is in the same state as before (below), and no sequence is below its source mark;
   - **the audit canaries confirm** under the retained key, and under a different key they do not (a lost key is a
     lost ability to confirm, `docs/RUNBOOK.md` 5.4);
   - **Flyway**: the migration `Job`'s profile runs as the owner, finds nothing pending, and `validate` passes;
   - **the application starts** as the runtime user, Flyway off, Hibernate `validate` on, and `/actuator/health`
     answers `UP` on the management port;
   - signed in as the Compliance Admin (a session as the session store holds one after login, so the roles are
     reloaded from the restored user tables), **the search finds the same cases** as on the source, and **the six
     downloads return the seeded bytes**;
   - the application **writes** to the restored copy (a new audit event: sequences and grants work);
   - **every attachment** read back as the runtime user has contiguous chunks adding up to its recorded size and
     SHA-256.

Any difference fails the drill.

### The commands

PostgreSQL (the container's local socket trusts its users; a deployment uses `~/.pgpass`):

    pg_dump -U <owner> -d <db> -Fc -f /tmp/trailkeep.dump
    pg_restore -U <owner> -d <db> --exit-on-error -j 4 /tmp/trailkeep.dump
    vacuumdb -U <owner> -d <db> --analyze-in-stages -j 4

Oracle, as `SYSTEM` (the restore needs the three grantees created first; it creates the owner itself):

    expdp system@//localhost:1521/<pdb> SCHEMAS=<owner> DIRECTORY=DATA_PUMP_DIR DUMPFILE=trailkeep.dmp \
      LOGFILE=trailkeep-expdp.log FLASHBACK_TIME=SYSTIMESTAMP REUSE_DUMPFILES=YES
    impdp system@//localhost:1521/<pdb> SCHEMAS=<owner> DIRECTORY=DATA_PUMP_DIR DUMPFILE=trailkeep.dmp \
      LOGFILE=trailkeep-impdp.log

The go-live index procedure, as the owner, with `GRANT EXECUTE ON CTXSYS.CTX_DDL TO <owner>` by `SYS` before and the
matching `REVOKE` after:

    ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS ('REPLACE METADATA SYNC (MANUAL)');
    -- the load
    EXEC CTX_DDL.SYNC_INDEX('IX_CASE_SEARCH_TEXT');
    SELECT COUNT(*) FROM CTX_USER_PENDING;          -- 0
    ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS ('REPLACE METADATA SYNC (ON COMMIT) OPTIMIZE (AUTO_DAILY)');

## Results

One desktop machine (the hardware of `docs/perf/release-500k.md`: a 16-core Ryzen 9 5900XT, 62 GB RAM, NVMe), the
databases in Docker with the images' default settings, both database containers on the same disk. The machine runs
other services, so times move by some percent between runs.

Times in seconds, from the final run of each (`target/restore-drill/*.md`). Earlier runs of the same code were within
a few seconds, except where noted.

| Step | PostgreSQL 16 | Oracle Free 23 | Oracle XE 21 |
|---|---:|---:|---:|
| Schema size (database, or the owner's segments) | 930 MiB | 1 429 MiB | 1 436 MiB |
| Rows (all tables) | 1 154 551 | 1 154 551 | 1 154 551 |
| **Backup** (`pg_dump -Fc` / `expdp`) | **26.3** | **50.1** | **33.5** |
| Backup file | 446.6 MiB | 878.8 MiB | 878.5 MiB |
| Copy off the database host (container to host) | 7.1 | 13.8 | 14.0 |
| New empty database started | 1.8 | 35.1 | 37.3 |
| Copy to the new host | 5.3 | 10.7 | 10.5 |
| **Restore** (`pg_restore -j 4` + `vacuumdb` / `impdp` with the index rebuild) | **13.4** (13.0 + 0.4) | **85.4** | **76.3** |
| Migration `Job` profile: Flyway validate, nothing pending | 0.8 | 1.9 | 2.7 |
| Application start (runtime user) | 0.8 | 1.3 | 1.3 |
| **Restore to serving** (copy in, restore, migration `Job`, start) | **20.4** | **99.2** | **90.8** |

Checking took, on top: the fingerprint of the restored copy 4.9 s / 25.0 s / 25.2 s, every attachment read back
2.6 s / 0.7 s / 0.8 s, the application checks 0.5 s / 1.3 s / 1.4 s.

The Oracle databases had three 512 MB redo groups. Restore to serving leaves out what a real recovery adds: deciding to
restore, provisioning the database (the containers' 35 s is not a real database's), moving the file across a network,
and the people.

Seeding, for the record (not part of the drill's targets):

| | PostgreSQL 16 | Oracle Free 23 | Oracle XE 21 |
|---|---:|---:|---:|
| 500k seed, as the owner | 55.0 | 254.9 (index on manual sync) | 310.8 (index on manual sync) |
| Rows waiting for the index after the load | – | 50 000 | 50 000 |
| `CTX_DDL.SYNC_INDEX` once | – | 87.1 (59.1 in an earlier run) | 75.5 (77.4) |
| V20's `IX_AUDIT_AT` rebuilt over 500 000 audit events | 0.2 | 1.7 (10.0 in an earlier run) | 1.6 |
| 2 000 attachments written | 3.0 | 10.8 | 10.5 |

All three runs passed every check listed above.

## Findings

1. **Every check passed on all three databases**: every table's rows and values, the search index's state, the
   sequences, Flyway, the application's start, search, downloads and writes, the audit canaries, every attachment.
   A logical backup with the vendor's own tool is a complete backup of Trailkeep; nothing lives outside the database
   but the Secrets.
2. **On Oracle 21, `REPLACE METADATA SYNC` drops `OPTIMIZE (AUTO_DAILY)` from the index's metadata.** The first XE 21
   run failed here: after the go-live procedure's `REPLACE METADATA SYNC (ON COMMIT)` the source still had `CTXSYS`'s
   two nightly jobs, but the export carried `PARAMETERS ('SYNC (ON COMMIT)')` only, and the restored index had no
   jobs. Reproduced by hand on a bare XE 21 table, where `REPLACE METADATA SYNC (ON COMMIT) OPTIMIZE (AUTO_DAILY)`
   keeps both and `REPLACE METADATA OPTIMIZE (AUTO_DAILY)` brings the jobs back on a restored index. Free 23 keeps the
   setting. The drill and the runbook now restore the index with both clauses, and the runbook's restore checks for
   the jobs and re-creates them. `docs/perf/importer-volume.md` (#314) names the single-clause statement.
3. **The schema owner cannot run `CTX_DDL.SYNC_INDEX` with `TRAILKEEP_OWNER`'s privileges.** `CTX_DDL` is granted to
   `CTXAPP` only; `PLS-00201` without it, on both images. `ALTER INDEX … REPLACE METADATA` needs nothing more. On
   Free 23 `SYSTEM` cannot grant `CTX_DDL` either (`ORA-00942`); `SYS` can, and on XE 21 `SYSTEM` can. The go-live
   procedure is therefore: a DBA grants `EXECUTE ON CTXSYS.CTX_DDL` to the owner, the owner switches, loads,
   synchronises and switches back, the DBA revokes. Switching back to `ON COMMIT` does not index the rows waiting
   (checked by hand on both images: they stay in `CTX_USER_PENDING` until the next commit synchronises them), so the
   explicit sync is required. 50 000 rows waited after the seed; one sync took 59 to 87 s.
4. **`impdp` rebuilds the Oracle Text index** from the imported rows (most of the restore's time) and brings back the
   locked `CASE_SEARCH` statistics, the index's absent statistics and, with the metadata intact, the nightly jobs.
   Sequences come back at or above their marks (Data Pump writes `LAST_NUMBER`, which includes the cache).
5. **`expdp` needs `FLASHBACK_TIME`** (or `FLASHBACK_SCN`) for a consistent copy of a running system; without it each
   table is exported as of its own moment. `pg_dump` is always consistent.
6. **A PostgreSQL dump carries no planner statistics**: `vacuumdb --analyze-in-stages` after `pg_restore` (under a
   second here). Not drilled, checked by hand on PostgreSQL 16: when a DBA created `pg_trgm` rather than the owner,
   the owner's `pg_restore --exit-on-error` stops at the extension's comment (`must be owner of extension pg_trgm`);
   leaving that one entry out of the restore list (`pg_restore -l | grep -v 'COMMENT - EXTENSION'`, `-L`) restores
   everything else, grants included. The runbook says so.
7. **On Oracle the fingerprint covers 70 tables, on PostgreSQL 46**: the 45 of the migrations and Flyway's history,
   plus, on Oracle, Hibernate's 24 empty `HTE_*` global temporary tables (for multi-table bulk statements). Hibernate
   creates them at start-up in the owner's schema, which only a context running as the owner (the migration `Job`'s)
   may do. They export and restore like any table. Not a defect, but they are DDL outside Flyway; recorded for the
   owner.
8. **Backup files**: the PostgreSQL custom format compresses (446.6 MiB for 930 MiB of database, most of it the
   incompressible attachments); Data Pump does not without the Advanced Compression option (878.8 MiB). The
   `pg_dump` default compression spends its time on attachment bytes that do not compress; `--compress=lz4` or `-Z 0`
   would be faster and larger (not measured).
9. **V20 at volume**: rebuilding `IX_AUDIT_AT` over the 500 000 audit events took 0.2 s on PostgreSQL and 1.6 to
   10 s on Oracle. Neither vendor builds it online, so audited writes wait for that long during the upgrade to 0.3.0 or
   later.
