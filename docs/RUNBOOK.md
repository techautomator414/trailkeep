<!-- SPDX-License-Identifier: Apache-2.0 -->
# Trailkeep operator's runbook

For whoever is on call for a Trailkeep deployment. Each procedure is a list of steps to carry out in order, with the
commands to copy, how to check that the step worked, and how to back out. `deploy/README.md` is the reference for the
manifests (what an overlay must set, every Secret, scanning mode, the importer `Job`, migrations, runtime properties
and each alert); this runbook links to it instead of repeating it.

Placeholders, which you replace:

| Placeholder | Meaning |
|---|---|
| `<ns>` | the Kubernetes namespace |
| `<overlay>` | your Kustomize overlay directory |
| `<digest>` | the image digest you deploy, `sha256:…` |
| `<owner>` | the schema owner (`TRAILKEEP_OWNER`, Oracle: also the schema name) |
| `<db>` | the PostgreSQL database |
| `<pdb>` | the Oracle service name (PDB) |
| `<dbhost>` | the database host |

Commands with `kubectl` work the same with `oc`. SQL marked *as the owner* runs as `TRAILKEEP_OWNER`; *as a DBA* means
an account the database administrators hold (Oracle `SYS` where stated: `SYSTEM` is not always enough).

## Contents

1. [What runs](#1-what-runs)
2. [Deploy and upgrade](#2-deploy-and-upgrade)
3. [Roll back](#3-roll-back)
4. [Back up and restore](#4-back-up-and-restore)
5. [Rotate secrets](#5-rotate-secrets)
6. [Scale and size the connection pool](#6-scale-and-size-the-connection-pool)
7. [Health, metrics and alerts](#7-health-metrics-and-alerts)
8. [Scheduled jobs](#8-scheduled-jobs)
9. [Rate limits](#9-rate-limits)
10. [Import go-live](#10-import-go-live)
11. [Incidents](#11-incidents)
12. [Disaster recovery](#12-disaster-recovery)
13. [Standing database decisions](#13-standing-database-decisions)

## 1. What runs

| Workload | Kind | Database user | When |
|---|---|---|---|
| `trailkeep` | `Deployment`, 2 replicas | `TRAILKEEP_APP` | always |
| `trailkeep-migrate` | `Job` | `TRAILKEEP_OWNER` | before every rollout ([2](#2-deploy-and-upgrade)) |
| `trailkeep-purge` | `CronJob`, 03:17 UTC, shipped suspended | `TRAILKEEP_PURGE` | once retention is decided |
| `trailkeep-importer` | `Job` | `TRAILKEEP_IMPORT` | one command per run ([10](#10-import-go-live)) |
| `trailkeep-importer-staging-ttl` | `CronJob`, 02:43 UTC | `TRAILKEEP_IMPORT` | while imports are in progress |
| `clamav` | `Deployment` with a TLS sidecar | none | scanning mode `clamd` only |

The database holds everything, attachment bytes included (BUILD_PLAN §16): one database backup is a complete backup.
Secrets are mounted as files from the platform's secret store
([deploy/README.md, step 4](../deploy/README.md#what-an-overlay-must-set)).

## 2. Deploy and upgrade

### 2.1 Before every upgrade

1. Read the release's `CHANGELOG.md` section, and the migration table in [2.3](#23-what-each-migration-costs) for every
   migration between your version and the new one.
2. Verify the image and note its digest (the release's `image-digest.txt`), with cosign v3 or later:

   ```sh
   cosign verify ghcr.io/techautomator414/trailkeep@<digest> \
     --certificate-identity 'https://github.com/techautomator414/trailkeep/.github/workflows/release.yml@refs/tags/<tag>' \
     --certificate-oidc-issuer https://token.actions.githubusercontent.com
   ```

   Check: cosign prints the verified claims and exits 0. Anything else: stop, do not deploy. The release's `VERIFY.md`
   has this command filled in, plus the SBOM, provenance and evidence-pack checks; `EVIDENCE.md` indexes the scan, test,
   performance and accessibility evidence your approval process may ask for ([docs/RELEASING.md](RELEASING.md)).
3. Check the database prerequisites in [13](#13-standing-database-decisions) (Oracle Text and a full image, redo
   logs, `JOB_QUEUE_PROCESSES`, `pg_trgm`).
4. **Take a backup** ([4.2](#42-postgresql) or [4.3](#43-oracle)) and keep it until the new release has run a day.
   Migrations are forward-only; this backup is the only way back from a migration ([3.2](#32-the-schema)).
5. Check the database session limit still covers replicas × 20 + 10 ([6](#6-scale-and-size-the-connection-pool)).

### 2.2 The upgrade

1. Set the new digest in `<overlay>/kustomization.yaml` (`images:` → `digest: <digest>`). The migration `Job` and the
   `Deployment` must name the same image.
2. Run the migration `Job` and wait for it:

   ```sh
   kubectl -n <ns> delete job trailkeep-migrate --ignore-not-found
   kubectl -n <ns> apply -k <overlay>/jobs/migrate     # your copy of deploy/jobs/migrate (or migrate-external-scanning)
   kubectl -n <ns> wait --for=condition=complete --timeout=60m job/trailkeep-migrate
   kubectl -n <ns> logs job/trailkeep-migrate | grep -E 'Successfully applied|Schema .* is up to date|ERROR'
   ```

   Check: the `Job` completes and the log says which migrations were applied, or that the schema is up to date.
   If it fails: read the log, fix the cause, delete the `Job` and run it again. **Do not roll out**, and never give the
   runtime user DDL to make a pod start ([deploy/README.md, "Migrations"](../deploy/README.md#migrations)). A failed
   V21 needs `flyway repair` first ([deploy/README.md, "Search indexes (V21)"](../deploy/README.md#search-indexes-v21)).
3. Roll out the web application:

   ```sh
   kubectl -n <ns> apply -k <overlay>
   kubectl -n <ns> rollout status deployment/trailkeep --timeout=10m
   ```

   Check: the rollout completes; `kubectl -n <ns> get pods -l app.kubernetes.io/component=web` shows every pod
   `Running` and ready; the System screen (`/admin/system`) shows each job's last success, the outbox and the scanner;
   open a case and run a search.
4. If the purge was enabled before, check the `CronJob` still names the new image (`kubectl -n <ns> get cronjob
   trailkeep-purge -o jsonpath='{..image}'`).

A fresh install is the same with the users and Secrets created first:
[deploy/README.md, "Migrations"](../deploy/README.md#migrations).

### 2.3 What each migration costs

Migrations since 0.2.0, measured at the release volume (50 000 cases, 500 000 comments, 500 000 audit events;
`docs/perf/release-500k.md`, `docs/ops/restore-drill.md`). Both are indexes with no entity change, so the previous
release keeps running while they build, and a rollback of the image alone works afterwards ([3.1](#31-the-application)).

| Migration | Release | What it does | PostgreSQL | Oracle |
|---|---|---|---|---|
| V20 `IX_AUDIT_AT` | 0.3.0 | B-tree on `AUDIT_EVENT (AT_TS, ID)` for the audit screen | 0.2 s at 500 000 audit events; **every audited write waits** for it (each change writes an audit row) | 1.6 to 10 s at 500 000 audit events; built without `ONLINE`, so audited writes wait as well |
| V21 `IX_CASE_SEARCH_TEXT` | 1.0.0 | search index on `CASE_SEARCH.SEARCH_TEXT` (ADR-0017) | trigram GIN, 13 s, 87 MB; **a ~13 s write pause on `CASE_SEARCH`** (posting a comment, editing a case) while it builds; needs `pg_trgm` created beforehand by the owner or a DBA | Oracle Text `CONTEXT`, built `ONLINE`: 17 to 33 s on Free 23, 95 s on XE 21 (20 MB redo), no write pause; needs Oracle Text installed (full image) |

Both pauses scale with the table: a deployment with ten times the audit rows waits about ten times as long for V20.
Run the upgrade in a quiet hour; the pause is a wait, not an error, and nothing is lost.

## 3. Roll back

### 3.1 The application

Roll back to the previous image when the new release misbehaves and its migrations changed no entity (each migration's
header comment says "No entity change" when that is so; V20 and V21 are such):

```sh
kubectl -n <ns> rollout undo deployment/trailkeep            # or set the previous <digest> in the overlay and apply
kubectl -n <ns> rollout status deployment/trailkeep --timeout=10m
```

Check: the pods run the previous digest (`kubectl -n <ns> get pods -o jsonpath='{..image}'`) and start; Hibernate's
`validate` passes at start-up only if the schema still matches the previous release's entities.

Do **not** run the previous release's migration `Job` against the newer schema: Flyway fails its validation on a
migration it does not know, by design. Leave the schema as it is. Put the purge `CronJob` back on the previous image
as well.

On Oracle, `APP_SEARCH_ADAPTER=like` in `trailkeep-config` (and a rollout restart) is the search kill switch: the
application stops using the Oracle Text index and searches with `LIKE`, as before 1.0.0, while the index is still
maintained. Use it, rather than a rollback, if only the search misbehaves.

### 3.2 The schema

Migrations only go forward (BUILD_PLAN §16). If a migration damaged data, or the previous release cannot run on the new
schema, the way back is the backup taken in [2.1](#21-before-every-upgrade): restore it ([4](#4-back-up-and-restore))
and deploy the previous image. Everything written since that backup is lost; say so to the business before you start.
Mail sent after the backup is pending again in the restored copy ([12.3](#123-after-any-restore)).

## 4. Back up and restore

### 4.1 What to keep

| What | Where it lives | Without it |
|---|---|---|
| The schema | the database | everything is gone |
| `app.audit.hmac-key` | `trailkeep-web` Secret | the restored audit trail's redacted values can no longer be confirmed against a known value; keep **every** key ever used ([5.4](#54-the-audit-hmac-key)) |
| `import.hash-key` | `trailkeep-import-hash-key` Secret | delta imports compare against nothing: every row looks changed |
| The other Secrets | secret store | re-creatable (new passwords, new OIDC secret) |
| The overlay | your configuration repository | re-creatable from `deploy/` |

Backups hold purged content until they expire, and personal data (THREAT_MODEL RR-1): encrypt them, restrict who can
read them, and expire them by your retention policy. The database administrators own the schedule (BUILD_PLAN §16);
the commands below are the drilled ones. Their timings at the release volume are in [4.5](#45-what-a-restore-takes).

### 4.2 PostgreSQL

**Back up** (a consistent snapshot of the database, taken online; the application keeps running):

```sh
pg_dump -h <dbhost> -U <owner> -d <db> -Fc -f trailkeep-$(date -u +%Y%m%dT%H%MZ).dump
pg_restore --list trailkeep-*.dump | grep -c 'TABLE DATA'      # check: one line per table, about 50
```

Pass the password through `~/.pgpass` or `PGPASSFILE`, never on the command line.

**Restore** into a new, empty database:

1. *As a DBA*: create the database and the four users with their passwords from the secret store (or
   `pg_dumpall --globals-only` taken from the old server). The users must exist before the restore: the dump's grants
   name them.

   ```sql
   CREATE DATABASE <db> OWNER <owner>;
   ```

   If the owner may create in the database (it owns it, or `GRANT CREATE ON DATABASE`), the dump creates `pg_trgm`
   itself. If a DBA creates `pg_trgm` instead (`\c <db>` then `CREATE EXTENSION IF NOT EXISTS pg_trgm;`), the owner may
   not restore the extension's comment, so leave it out of the restore in step 2:
   `pg_restore -l trailkeep-<stamp>.dump | grep -v 'COMMENT - EXTENSION' > restore.list`, then add `-L restore.list`
   to the `pg_restore` command.
2. Restore, then give the planner its statistics (a dump carries none):

   ```sh
   pg_restore -h <dbhost> -U <owner> -d <db> --exit-on-error -j 4 trailkeep-<stamp>.dump
   vacuumdb -h <dbhost> -U <owner> -d <db> --analyze-in-stages -j 4
   ```

   Check: both exit 0. If `pg_restore` stops, drop and re-create the database and start again; a half-restored database
   is not used.
3. Verify ([4.4](#44-verify-a-restore)).

To lose less than a day, the database administrators add continuous archiving (WAL archiving with base backups,
point-in-time recovery). Trailkeep needs nothing for it; the logical dump stays the portable copy.

### 4.3 Oracle

**Back up** with Data Pump, *as a DBA* (a user with `DATAPUMP_EXP_FULL_DATABASE`), into a directory object:

```sh
cat > trailkeep-expdp.par <<'EOF'
SCHEMAS=<owner>
DIRECTORY=DATA_PUMP_DIR
DUMPFILE=trailkeep.dmp
LOGFILE=trailkeep-expdp.log
FLASHBACK_TIME=SYSTIMESTAMP
EOF
expdp <dba>@//<dbhost>:1521/<pdb> PARFILE=trailkeep-expdp.par
```

`FLASHBACK_TIME` is not optional: without it each table is exported as of a different moment, and a case can arrive
without its comments. The undo retention must cover the export's duration. Check: the log ends with `successfully
completed` and holds no `ORA-` line.

**Restore** into a new database or PDB (same release of Oracle or later, Oracle Text installed,
[13](#13-standing-database-decisions)):

1. *As a DBA*: create `TRAILKEEP_APP`, `TRAILKEEP_PURGE` and `TRAILKEEP_IMPORT` (the dump's grants name them). Do not
   create the owner: the import creates it, with its password and privileges. Size the redo logs and check
   `JOB_QUEUE_PROCESSES` ([13](#13-standing-database-decisions)) now, before the import writes.
2. Copy the dump file into the directory object's path on the database host and import, *as a DBA*:

   ```sh
   impdp <dba>@//<dbhost>:1521/<pdb> SCHEMAS=<owner> DIRECTORY=DATA_PUMP_DIR DUMPFILE=trailkeep.dmp \
     LOGFILE=trailkeep-impdp.log
   ```

   The import rebuilds the Oracle Text index from the imported rows; that is most of its time. Check: `successfully
   completed`, no `ORA-` line. If not: `DROP USER <owner> CASCADE` and import again.
3. *As the owner*, check the search index and its settings came back:

   ```sql
   SELECT STATUS, DOMIDX_STATUS, DOMIDX_OPSTATUS FROM USER_INDEXES WHERE INDEX_NAME = 'IX_CASE_SEARCH_TEXT';
   -- VALID VALID VALID
   SELECT IDX_STATUS, IDX_SYNC_TYPE FROM CTX_USER_INDEXES WHERE IDX_NAME = 'IX_CASE_SEARCH_TEXT';
   -- INDEXED ON COMMIT
   SELECT COUNT(*) FROM CTX_USER_PENDING;                      -- 0
   SELECT COUNT(*) FROM CTX_USER_INDEX_ERRORS;                 -- 0
   SELECT STATTYPE_LOCKED FROM USER_TAB_STATISTICS WHERE TABLE_NAME = 'CASE_SEARCH';   -- ALL
   ```

   and, *as a DBA*, that `CTXSYS` has the index's two nightly jobs again:
   `SELECT JOB_NAME, ENABLED FROM DBA_SCHEDULER_JOBS WHERE OWNER = 'CTXSYS' AND JOB_NAME LIKE 'DR$%';` (two rows,
   `TRUE`). If there are none (an index whose sync was switched on Oracle 21 without naming `OPTIMIZE`,
   [10](#10-import-go-live) step 7), *as the owner*:
   `ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS ('REPLACE METADATA OPTIMIZE (AUTO_DAILY)');` and check again. If the statistics are not locked, lock them ([13](#13-standing-database-decisions)).
4. Verify ([4.4](#44-verify-a-restore)).

For point-in-time recovery the database administrators run RMAN with archive logging; Trailkeep needs nothing for it.

### 4.4 Verify a restore

1. Run the migration `Job` for the image you will deploy ([2.2](#22-the-upgrade), step 2). Check: it completes and its
   log says the schema is up to date. It validates every applied migration's checksum; a restore that lost or changed
   one fails here.
2. Roll out the application and check: pods ready, `/actuator/health` on port 8081 `UP`, the System screen.
3. Sign in as a Compliance Admin: search for a word you know is in comments; open a case with attachments and download
   one; open the audit screen for the last day before the backup.
4. Compare row counts with the source if it still exists (`SELECT COUNT(*)` on `CASE_RECORD`, `CASE_COMMENT`,
   `CASE_ATTACHMENT`, `AUDIT_EVENT`).

### 4.5 What a restore takes

Drilled at the release volume plus 2 000 attachments (348 MiB, incompressible) on one desktop machine; method and full
tables in [docs/ops/restore-drill.md](ops/restore-drill.md). BUILD_PLAN sets no RPO or RTO: they are the deploying
organization's ("backups per organization policy").

| | PostgreSQL 16 | Oracle Free 23 | Oracle XE 21 |
|---|---:|---:|---:|
| Database size backed up | 930 MiB | 1 429 MiB | 1 436 MiB |
| Backup time | 26 s | 50 s | 34 s |
| Backup file | 447 MiB | 879 MiB | 879 MiB |
| Restore time (incl. statistics / index rebuild) | 13 s | 85 s | 76 s |
| Restore to serving (copy in, restore, migration `Job`, application start) | 20 s | 99 s | 91 s |

With logical backups the data lost in a disaster (the RPO) is everything since the last backup: a nightly backup
loses up to a day. Restore time grows with the data; scale these by your database's size, and add the time to
provision the database and move the file.

## 5. Rotate secrets

Every Secret is a set of files under `/etc/trailkeep/secrets/`, read at start-up. A change takes effect after a restart:

```sh
kubectl -n <ns> rollout restart deployment/trailkeep
kubectl -n <ns> rollout status deployment/trailkeep --timeout=10m
```

### 5.1 Database passwords

For each of `TRAILKEEP_APP`, `TRAILKEEP_PURGE`, `TRAILKEEP_IMPORT`, `TRAILKEEP_OWNER`:

1. *As a DBA*, set the new password: `ALTER USER <user> IDENTIFIED BY "<new>";` (Oracle) or
   `ALTER ROLE <user> PASSWORD '<new>';` (PostgreSQL). Sessions already open keep working.
2. Update `spring.datasource.password` in that user's Secret (`trailkeep-web`, `trailkeep-purge`, `trailkeep-import`,
   `trailkeep-migrate`).
3. Restart what mounts it: the web `Deployment`; the `CronJob`s and `Job`s pick it up on their next run.

Check: the pods become ready (a wrong password fails start-up, and the old pods keep serving). Roll back: set the old
password again in the database. Keep `TRAILKEEP_OWNER` and `TRAILKEEP_IMPORT` locked (`ALTER USER … ACCOUNT LOCK`,
`ALTER ROLE … NOLOGIN`) when no migration or import runs (THREAT_MODEL RR-2).

### 5.2 OIDC client secret

1. In the identity provider, add a second secret to the Trailkeep client (or generate a new one if it allows only one;
   then sign-ins fail between steps 1 and 3).
2. Update the client secret file in `trailkeep-web`; restart the `Deployment`.
3. Sign in; then revoke the old secret in the identity provider.

Signed-in sessions are not affected: the secret is used only when someone signs in.

### 5.3 SMTP password

Update `spring.mail.password` in `trailkeep-web` (and in the relay) and restart. Mail written meanwhile waits in the
outbox and is sent once the relay accepts the new password; `TrailkeepMailNotSending` fires if it does not.

### 5.4 The audit HMAC key

`app.audit.hmac-key` turns every redacted audit value (a case's description, subject, comment bodies; legacy history)
into `HMAC-SHA-256(value)`. The digest lets a Compliance Admin confirm that a value is the one recorded, without the
trail ever holding the text. **Rotating the key does not change any stored value.** Values written before the
rotation stay digests under the old key, and can be confirmed only with the old key; values written after use the new
one. So:

1. Rotate only when the key may have leaked (a leaked key lets anyone test short values by dictionary, RR-2), not on a
   calendar.
2. Before rotating, store the old key in the secret store's archive with the date range it covered. Never delete it.
3. Generate the new key: `openssl rand -base64 32`. Put it in `trailkeep-web` as `app.audit.hmac-key`.
4. Restart the web `Deployment`. The importer `Job` and the staging TTL `CronJob` read the same Secret: run no import
   during the rotation, so no run mixes keys.
5. Record the moment of the restart (the first audit event under the new key is the first one after it).

During the rolling restart, old and new pods write under different keys for a minute or two; note that window too.
The restore drill confirms redacted values under the retained key and shows that no other key confirms them
([docs/ops/restore-drill.md](ops/restore-drill.md)).

### 5.5 Other keys

- `import.hash-key`: rotate only between imports; a delta run after a rotation sees every row as changed
  (`docs/design/importer.md`, §10).
- The ClamAV TLS certificate (`clamav-tls`, `clamav-ca`): renewed by your certificate issuer; a replaced CA must reach
  `clamav-ca` before the old one expires, or uploads are refused (`TrailkeepScannerDown`).

## 6. Scale and size the connection pool

Each web replica keeps **20** database connections, always (`spring.datasource.hikari.maximum-pool-size`, measured in
`docs/perf/release-500k.md`, "Pool sizing"): 8 for attachment downloads that stream, about 10 for interactive requests.
The database must allow:

    sessions for TRAILKEEP_APP = replicas × 20
    total                      = replicas × 20 + 10   (migration, purge, importer and staging TTL Jobs, each a few)

Two replicas: 50. Four replicas: 90. On Oracle that is `SESSIONS` and `PROCESSES` and any profile limit on
`TRAILKEEP_APP`; on PostgreSQL `max_connections` (less the superuser reserve) and any `CONNECTION LIMIT` on the role.

To scale out:

1. Raise the database limit first, by 20 per added replica.
2. `kubectl -n <ns> scale deployment/trailkeep --replicas=<n>`, or set `replicas` in the overlay.
3. Raise the platform's rate limit at the route ([9](#9-rate-limits)); keep the `PodDisruptionBudget` below `<n>`.

Check: new pods ready; `hikaricp_connections_pending` stays 0. Do not lower the pool below
`app.attachments.max-concurrent-downloads` + 10, or slow downloads starve the replica.

## 7. Health, metrics and alerts

- Probes, port 8081: `/actuator/health/liveness`, `/actuator/health/readiness` (pod-local members only).
  `/actuator/health` adds the database.
- Metrics: `/actuator/prometheus`, port 8081, admitted only from cluster monitoring.
- The System screen, `/admin/system` (System Admins, Compliance Admins): each job's last success, the outbox, the
  scanner.

The alert rules are `deploy/components/prometheus-rules`. What each means and what to do is in
[deploy/README.md, "Alerts"](../deploy/README.md#alerts); in short:

| Alert | Means | First look |
|---|---|---|
| `TrailkeepSlaEvaluatorLate` | no SLA evaluation for 30 min: states and alerts are stale | pods running? database reachable? `SHEDLOCK` row |
| `TrailkeepRemindersLate`, `TrailkeepReportDeliveryLate`, `TrailkeepArchiveLate` | that job has not succeeded for 2 h | the same |
| `TrailkeepMailNotSending`, `TrailkeepMailBacklog` | the relay refuses or is unreachable; mail queues | egress policy, `spring.mail.host`, the relay |
| `TrailkeepMailFailed` | a mail used its 10 attempts | its `LAST_ERROR` on the System screen ([8.2](#82-mail-that-failed)) |
| `TrailkeepDbPoolExhausted` | requests queue for a connection for 5 min | [6](#6-scale-and-size-the-connection-pool), database locks |
| `TrailkeepScannerDown` | clamd unanswered for 5 min; uploads refused | ClamAV pod, TLS Secrets |
| `TrailkeepPurgeNotSucceeding` | the purge has not completed in 36 h | its last `Job`'s log |

## 8. Scheduled jobs

### 8.1 What runs when

| Job | Where | Schedule | If it fails |
|---|---|---|---|
| SLA evaluator | web replicas, one at a time (ShedLock) | every 15 min | next run catches up; nothing to replay |
| Reminders | web, ShedLock | hourly at :07 | the same |
| Report delivery | web, ShedLock | hourly at :23, sends what `REPORT_SCHEDULE` says is due | the same |
| Archive (retention) | web, ShedLock | hourly at :37 | the same |
| Outbox dispatcher | every web replica | every minute | retries with backoff, 10 attempts |
| Scanner probe | every web replica | every minute | `TrailkeepScannerDown` |
| Purge | `CronJob trailkeep-purge` | 03:17 UTC, suspended until enabled | the next run purges everything due |
| Staging TTL | `CronJob trailkeep-importer-staging-ttl` | 02:43 UTC | the next run deletes what has expired |

A job's last success is in `JOB_RUN` and on the System screen. A lock held by a pod that died is released at its
`LOCK_UNTIL` in `SHEDLOCK`; do not delete the row by hand.

```sh
kubectl -n <ns> logs deployment/trailkeep --since=2h | grep -i '<job name>'
kubectl -n <ns> get jobs -l app.kubernetes.io/component=purge
kubectl -n <ns> create job --from=cronjob/trailkeep-purge trailkeep-purge-manual    # run the purge now
```

### 8.2 Mail that failed

A `FAILED` mail stays in `NOTIFICATION_OUTBOX` with its `LAST_ERROR`; it is not retried. Fix the cause (usually a
recipient the relay rejects), then send it again from **Failed mail** (`/admin/outbox`, linked from the Compliance
Admin screens): a Compliance Admin ticks the mails and chooses *Send selected again*. At most 50 at a time, oldest
first; recipients are shown partly hidden. Each mail goes back to `PENDING` with its attempts reset, the dispatcher
sends it within a minute, and one `MAIL_REQUEUED` event per mail (who, when, which row, the previous attempts and
failure reason) is in the audit trail (Audit trail, entity `NOTIFICATION_OUTBOX`). Two people choosing the same mail
move it once. A System Admin gets 403.

**Break-glass only**, when the application cannot be used: *as the owner or the runtime user*,

```sql
UPDATE NOTIFICATION_OUTBOX SET STATUS = 'PENDING', ATTEMPTS = 0, NEXT_ATTEMPT_AT = CURRENT_TIMESTAMP, LAST_ERROR = NULL
 WHERE STATUS = 'FAILED' AND ID IN (<ids>);
```

This change is not audited by the application: record it in the change ticket.

### 8.3 Reports and quarantined files

- **An aging report now**: Reports → Aging, on demand, for any team you may read. The scheduled mail is the next
  hourly tick after the team's `NEXT_RUN_AT` (Reports → Schedules).
- **A quarantined file**: a Compliance Admin opens the case's Attachments tab, chooses Release and gives a reason; the
  release is audited.

## 9. Rate limits

Search and its CSV export allow each person 120 requests, refilled at 120 a minute; uploads 30, refilled at 30 per
10 minutes (`app.rate-limit.search.*`, `app.rate-limit.upload.*`). Over the limit the request is answered 429 with
`Retry-After`, counted on `trailkeep_rate_limit_refusals_total{route}` and logged with the person's id. The buckets
are per replica and in memory, so with N replicas a person gets up to N times the limit and a restart refills them:
set a per-client limit at the route or ingress as well. A person legitimately limited (a bulk export) waits a minute;
raise the property only for everyone, by a redeploy. `app.rate-limit.enabled=false` is refused by the `prod` profile.
Detail: [deploy/README.md, "Runtime properties"](../deploy/README.md#runtime-properties).

## 10. Import go-live

The importer's commands and screens are in [deploy/README.md, "Importer"](../deploy/README.md#importer) and the
worked example `docs/importer/worked-example.md`. Go-live is the last run against a frozen source:

1. **Weeks before**: at least two dry runs against a production-like copy (BUILD_PLAN §15), signed off.
2. **The day before**: brief management on the wave of Overdue cases (the SLA comes from each case's original open
   date); check the database prerequisites ([13](#13-standing-database-decisions)), and the redo logs especially.
3. **Freeze** the legacy tracker (no writers). Take a backup of Trailkeep ([4](#4-back-up-and-restore)).
4. Unlock `TRAILKEEP_IMPORT`. Run `validate-mapping`, `extract`, `dry-run`; the Compliance Admin signs the run off on
   the Import runs screen.
5. **Oracle only: put the search index on manual sync for the load.** Otherwise about 85 % of the load is Oracle Text
   synchronising the index at every case's commit (`docs/perf/importer-volume.md`, #314). This is the schema owner's
   step and a DBA's grant for its duration, never a grant to the import `Job`:
   1. *As a DBA* (`SYS` on Oracle 23; `SYSTEM` may grant it on 21, not on 23):
      `GRANT EXECUTE ON CTXSYS.CTX_DDL TO <owner>;`
   2. *As the owner*:

      ```sql
      ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS ('REPLACE METADATA SYNC (MANUAL)');
      SELECT IDX_SYNC_TYPE FROM CTX_USER_INDEXES WHERE IDX_NAME = 'IX_CASE_SEARCH_TEXT';    -- MANUAL
      ```

   Searches keep finding loaded cases during the load (the index stays `TRANSACTIONAL`), only more slowly.
6. Run `load`, **just before the cut-over** (ADR-0016: alerts are pre-marked as of the load).
7. **Oracle only: synchronise and restore the index**, *as the owner*:

   ```sql
   EXEC CTX_DDL.SYNC_INDEX('IX_CASE_SEARCH_TEXT');
   SELECT COUNT(*) FROM CTX_USER_PENDING;                                                     -- 0
   ALTER INDEX IX_CASE_SEARCH_TEXT PARAMETERS ('REPLACE METADATA SYNC (ON COMMIT) OPTIMIZE (AUTO_DAILY)');
   SELECT IDX_SYNC_TYPE FROM CTX_USER_INDEXES WHERE IDX_NAME = 'IX_CASE_SEARCH_TEXT';    -- ON COMMIT
   ```

   then, *as a DBA*, `REVOKE EXECUTE ON CTXSYS.CTX_DDL FROM <owner>;`. Do not skip the sync: switching back to
   `ON COMMIT` does not index the rows already waiting; the first commit afterwards would, inside a user's request.
   If `CTX_USER_PENDING` is not 0 after the sync, run it again and check `CTX_USER_INDEX_ERRORS`. Leave `CASE_SEARCH`'s
   statistics locked ([13](#13-standing-database-decisions)).

   Name `OPTIMIZE (AUTO_DAILY)` in the last `ALTER INDEX`: on Oracle 21 `REPLACE METADATA SYNC` drops it from the
   index's metadata. The nightly jobs keep running, but the next restore recreates the index without them (found by
   the restore drill, `docs/ops/restore-drill.md`).
8. Run `reconcile` **before the cut-over opens** (design §9, N-i); the verdict must be `RECONCILED`.
9. Open Trailkeep to users. Run `purge-staging`. Lock `TRAILKEEP_IMPORT`. Take a backup.
10. Delete the staging TTL `CronJob` once `status` shows every run's staging purged; keep `trailkeep-import-hash-key`
    while a delta run may still compare with the ledger.

Back out: until users start writing, restore the backup of step 3. Afterwards, a run's loaded cases stay; fix forward
with a delta run.

## 11. Incidents

- **Logs**: one JSON object per line on stdout (`kubectl -n <ns> logs deployment/trailkeep --since=1h`), with
  `requestId`, `userId` and `caseNumber` where known. Search terms and case data are not logged; the router's access
  log may hold query strings ([deploy/README.md, "Runtime properties"](../deploy/README.md#runtime-properties)).
- **Request ids**: every response carries `X-Request-Id` (the caller's, if it sent a well-formed one). Ask the person
  for it, or take it from the router log, then:
  `kubectl -n <ns> logs -l app.kubernetes.io/component=web --since=24h | grep '"requestId":"<id>"'`.
- **The audit trail**: every change, restricted view and download, with actor, time, request id and client address.
  Compliance Admins search and export it on the Audit screen (time range required). A DBA can look one request up:
  `SELECT AT_TS, ACTOR_ID, ACTION, ENTITY_TYPE, ENTITY_ID FROM AUDIT_EVENT WHERE REQUEST_ID = '<id>';`.
  It is append-only for every application user, not tamper-evident to the owner or a DBA (RR-8).
- **Contain**: disable a user on the Users screen (their sessions end at once); stop mail with `app.mail.enabled=false`
  (it queues, nothing is lost); suspend the purge (`kubectl -n <ns> patch cronjob trailkeep-purge -p
  '{"spec":{"suspend":true}}'`); switch the Oracle search to `LIKE` (`APP_SEARCH_ADAPTER=like`).
- **Report a vulnerability**: `SECURITY.md`.

## 12. Disaster recovery

### 12.1 Which case are you in

| Lost | Do |
|---|---|
| A pod or a node | nothing: the `Deployment` replaces it, sessions live in the database |
| The namespace or the cluster | re-create the Secrets from the store, apply the overlay; the database is untouched ([2.2](#22-the-upgrade), steps 2 and 3) |
| The database, or its data is damaged | [12.2](#122-a-lost-database) |
| `app.audit.hmac-key` with no archived copy | set a new one ([5.4](#54-the-audit-hmac-key)); existing redacted values can never be confirmed again; record it |

### 12.2 A lost database

1. Declare the incident; stop the web `Deployment` (`kubectl -n <ns> scale deployment/trailkeep --replicas=0`) and
   suspend the `CronJob`s, so nothing writes to a half-recovered database.
2. Provision a new database meeting [13](#13-standing-database-decisions).
3. Restore the newest good backup ([4.2](#42-postgresql), [4.3](#43-oracle)).
4. Point the overlay at the new database (the JDBC URL in `trailkeep-config`, the address in every `NetworkPolicy`).
5. Set `app.mail.enabled=false` for now ([12.3](#123-after-any-restore)).
6. Run the migration `Job` for the image that was running, then scale the `Deployment` back up; verify
   ([4.4](#44-verify-a-restore)).
7. Do [12.3](#123-after-any-restore), then turn mail back on.

Drilled timings: [4.5](#45-what-a-restore-takes).

### 12.3 After any restore

The database is back at the moment of the backup. Everything after it is gone, and some of it already left the system:

- **Mail**: mail sent after the backup is `PENDING` again in the restored outbox, and SLA alerts sent after it are
  missing from the alert ledger, so both would be sent again. Review the outbox before you turn mail back on; delete
  or mark sent what went out (record the change).
- **Purges**: cases purged after the backup are back. Run the purge at once
  (`kubectl -n <ns> create job --from=cronjob/trailkeep-purge trailkeep-purge-after-restore`): it purges everything past
  its date again.
- **Imports**: a run loaded after the backup is gone from the ledger as well; run `load` again for it (idempotent).
- **People**: tell users which period was lost so they re-enter it.

## 13. Standing database decisions

Decisions that hold for every deployment (2026-11-23), with where they come from:

- **Oracle images must be full, not `-slim`.** The slim images remove Oracle Text, which V21 and the search need.
  Check: `SELECT STATUS FROM DBA_REGISTRY WHERE COMP_ID = 'CONTEXT'` → `VALID`.
- **Oracle redo: at least three groups of 512 MB** (#308, #310). With the containers' 20 MB groups one comment post in
  sixty stalled for about a minute at the release volume. Check: `SELECT GROUP#, BYTES/1048576, STATUS FROM V$LOG`.
  Fix, *as a DBA*: `ALTER DATABASE ADD LOGFILE GROUP <n> ('<path>') SIZE 512M;` three times, `ALTER SYSTEM SWITCH
  LOGFILE;` until the small groups are `INACTIVE`, then `ALTER DATABASE DROP LOGFILE GROUP <old>;`.
- **`CASE_SEARCH` statistics stay locked** (ADR-0017). With statistics, Oracle Text runs every search twice. Never
  unlock them or gather with `force => TRUE`. Check: `STATTYPE_LOCKED = 'ALL'` ([4.3](#43-oracle)). If someone did,
  *as the owner*: `EXEC DBMS_STATS.DELETE_INDEX_STATS(USER, 'IX_CASE_SEARCH_TEXT', force => TRUE);` and
  `EXEC DBMS_STATS.LOCK_TABLE_STATS(USER, 'CASE_SEARCH');`.
- **`JOB_QUEUE_PROCESSES` > 0** on Oracle, for the index's nightly `OPTIMIZE (AUTO_DAILY)` by `CTXSYS`. Check:
  `SELECT VALUE FROM V$PARAMETER WHERE NAME = 'job_queue_processes'`, and that `CTXSYS` has the index's two `DR$…`
  jobs ([4.3](#43-oracle), step 3).
- **The search kill switch** `APP_SEARCH_ADAPTER=like` ([3.1](#31-the-application)).
- **Oracle Text and the initial import**: manual sync around the go-live load, by the schema owner, with `CTX_DDL`
  granted for that window only ([10](#10-import-go-live), steps 5 and 7).
- **PostgreSQL V21**: a ~13 s write pause on `CASE_SEARCH` while the trigram index builds at the release volume
  ([2.3](#23-what-each-migration-costs)); `pg_trgm` is created by the schema owner or a DBA beforehand
  (`CREATE EXTENSION IF NOT EXISTS pg_trgm;`).
