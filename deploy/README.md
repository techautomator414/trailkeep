<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Deploying Trailkeep

Reference manifests for OpenShift, usable on plain Kubernetes. They follow BUILD_PLAN §16. Everything marked
`PLACEHOLDER` is set by the deploying organization in its own overlay; nothing here is specific to one site.

## Layout

| Path | What it is |
|---|---|
| `base/` | Kustomize base: `Deployment` (2 replicas), `Service`, `Route`, `NetworkPolicy`, `ConfigMap`, `ServiceAccount`, `PodDisruptionBudget`, and the suspended purge `CronJob`. |
| `base/ingress-alternative.yaml` | Commented `Ingress` to use instead of `route.yaml` on plain Kubernetes. |
| `overlays/external-scanning/` | Example overlay without ClamAV, for `APP_ATTACHMENTS_SCANNING=external` (see "Scanning mode"). |
| `components/prometheus-rules/` | Optional Kustomize component with the `PrometheusRule` for the operational alerts (see "Alerts"). |
| `jobs/importer/` | The importer `Job` and its `NetworkPolicy`, applied by hand because applying a `Job` runs it (see "Importer"). |
| `jobs/importer-external-scanning/` | The same for `APP_ATTACHMENTS_SCANNING=external`. |
| `jobs/components/source-access/` | Kustomize component that gives the importer `Job` the source password and the route to the source, together; for `validate-mapping` and `extract` only. |
| `jobs/importer-source-access/`, `jobs/importer-external-scanning-source-access/` | Each variant with that component applied. |

Render and check locally:

```sh
kubectl kustomize deploy/base
kube-linter lint deploy/
```

## What an overlay must set

1. **Namespace and image.** `namespace:` and an `images:` entry pinning `ghcr.io/techautomator414/trailkeep` by
   digest. Verify the signature first: `cosign verify ghcr.io/techautomator414/trailkeep@sha256:… \
   --certificate-identity-regexp '^https://github.com/techautomator414/trailkeep/' \
   --certificate-oidc-issuer https://token.actions.githubusercontent.com`.
2. **`APP_PROXY_PATTERN`** in `trailkeep-config`: a regular expression for the router's pod addresses. Only these
   peers may set `X-Forwarded-For`/`X-Forwarded-Proto`. The app does not start without it.
   **`APP_DB_APP_USER`**, **`APP_DB_PURGE_USER`** and **`APP_DB_IMPORT_USER`**: the existing database users that the
   vendor `R__grants.sql` grants to (runtime, retention purge and importer Job). The app does not start without them.
   The import user may stay locked until an import is run.
   **`APP_DB_SCHEMA`**: the schema that owns the tables. Required on Oracle, where those three users own nothing and
   every connection must default to the owner's schema (the application, the purge and the importer all read it from
   `trailkeep-config`); on PostgreSQL it is the schema `search_path` points at, for example `public`.
3. **NetworkPolicy targets** in `trailkeep-egress`: database, identity provider, SMTP relay and clamd. The base uses
   RFC 5737 documentation addresses.
4. **Secrets.** Create them from the platform's secret store; the manifests only reference them. Each key is a file
   named after the Spring property it sets (Spring `configtree`), mounted at `/etc/trailkeep/secrets/`:

   | Secret | Mounted by | Keys (added by the work packages that need them) |
   |---|---|---|
   | `trailkeep-web` | web `Deployment`; the importer `Job` takes `app.audit.hmac-key` alone | `spring.datasource.username`, `spring.datasource.password`, OIDC client secret, SMTP password, `app.audit.hmac-key` (Base64, ≥ 32 random bytes; the `prod` profile refuses to start without it) |
   | `trailkeep-purge` | purge `CronJob` only | `spring.datasource.username`, `spring.datasource.password`: the `TRAILKEEP_PURGE` credential. Nothing else: the purge registers no OIDC client, and its `CASE_PURGED` audit rows hold counts only, so it needs neither the client secret nor `app.audit.hmac-key` |
   | `trailkeep-import` | importer `Job` only | `spring.datasource.username`, `spring.datasource.password`: the `TRAILKEEP_IMPORT` credential. Only these two keys are projected, whatever else the Secret holds: the importer registers no OIDC client and never sees the web application's credential |
   | `trailkeep-import-source` | importer `Job` with the `source-access` component only (`validate-mapping`, `extract`) | `import.source.password`: the legacy source account's password. `load`, `reconcile`, `relink-owners` and the other commands never mount it |
   | `trailkeep-import-hash-key` | importer `Job` only | `import.hash-key` (Base64, ≥ 32 random bytes, a different value from `app.audit.hmac-key`): keys the row and key hashes in staging, issues and the import ledger. Keep it for as long as later runs compare with the ledger; a new key makes every delta row look changed |
   | `clamav-tls` | ClamAV `Deployment` (TLS sidecar) only | `tls.crt`, `tls.key`: the sidecar's server certificate, naming the host in `APP_ATTACHMENTS_CLAMD_HOST` (the `clamav` Service by default); for example a cert-manager `Certificate` with `secretName: clamav-tls` (ADR-0012) |
   | `clamav-ca` | web `Deployment`, importer `Job` (clamd mode), purge `CronJob` | `ca.crt` only: the CA that issued `clamav-tls`, loaded as the `clamd` SSL bundle; never the key |

5. **Scanning mode.** See below; the default needs nothing.
6. **Purge schedule.** Set `suspend: false` on `trailkeep-purge` once retention is enabled. Each run tombstones the archived cases whose `PURGE_AFTER` has passed and that are not on legal hold (comments, attachments, search text, update and closure requests and their mail deleted; description and subject cleared; audit trail, alert ledger and legal hold history kept), deletes report runs older than `APP_RETENTION_REPORT_RUNS` (default 400 days) with the mail that names them, logs counts only and exits.

## Scanning mode

`APP_ATTACHMENTS_SCANNING` (`app.attachments.scanning`, ADR-0013) chooses who scans uploads for malware. It is read
at startup, so changing it is a redeploy and shows in change control; nothing in the application can change it.

- **`clamd`** (the default, also when unset): every upload is scanned by the ClamAV in `base/clamav.yaml` over TLS
  before it is stored, and refused while the scanner is unreachable. Everything in step 3 and 4 above applies.
- **`external`**: for organizations that scan upstream of the application (a web gateway or an ICAP proxy) or at
  the user endpoints. Trailkeep still enforces the size cap, the type allow-list, the extension cross-check and the
  image cap, but calls no scanner; files are stored as `NOT_SCANNED`, shown as "Not scanned by Trailkeep (scanned
  upstream)", never as clean, and the upload's audit entry records the mode. The application logs a warning at
  startup. An unknown value fails startup.

To run without ClamAV, point your overlay's `resources:` at `overlays/external-scanning` instead of `base`, or copy
its patches. It sets `APP_ATTACHMENTS_SCANNING: external` and removes:

- the ClamAV `Deployment`, `Service` and `NetworkPolicy` (`base/clamav.yaml`);
- `APP_ATTACHMENTS_CLAMD_HOST` and `SPRING_SSL_BUNDLE_PEM_CLAMD_TRUSTSTORE_CERTIFICATE` from `trailkeep-config`;
- the `clamav-ca` volume and mount from the web `Deployment` and the purge `CronJob`;
- the ClamAV egress rule from `trailkeep-egress`.

The `clamav-tls` and `clamav-ca` Secrets are then not created. For the importer, apply
`jobs/importer-external-scanning` instead of `jobs/importer`. Make sure every path into the application passes your scanner: a
file that bypasses it is stored unscanned and can be downloaded.

## Importer

The importer (`docs/design/importer.md`) runs one command for one run per `Job` run, as the `TRAILKEEP_IMPORT`
database user, with the same image and the `prod,importer` profiles. Run requests and sign-offs are made on the Import
runs screen; the `Job` only carries them out.

1. **Claims.** The operator prepares three `PersistentVolumeClaim`s; the manifests only reference them, so deleting
   the `Job` never deletes their data:
   - `trailkeep-import-data`, mounted read-only: the reviewed mapping file (`IMPORT_MAPPING`), lookups, extract files
     and attachment folders;
   - `trailkeep-import-content`, writable: each run's content area, the attachment bytes a content query fetched at
     extract. It holds legacy content, so it must be encrypted at rest; `purge-staging` and the staging TTL delete each
     run's folder. Size it for the largest run's source-query attachments;
   - `trailkeep-import-reports`, writable: each run's report files under `<run id>/`, counts, codes, keyed hashes and
     Trailkeep ids only. They outlive the staging, for the approver.

   OpenShift's `restricted-v2` SCC assigns an `fsGroup`, which makes the writable claims writable; on plain
   Kubernetes set `fsGroup` in your overlay. Each claim's root is used as it is: it must be a directory, never a
   symbolic link.
2. **Secrets.** `trailkeep-import` and `trailkeep-import-hash-key` (table above), and `app.audit.hmac-key` from
   `trailkeep-web`, all projected into `/etc/trailkeep/secrets/`; `trailkeep-import-source` joins them only with the
   `source-access` component.
3. **Command.** Set `IMPORT_COMMAND` and `IMPORT_RUN` in the `Job`, apply the directory for that command, and read
   the outcome in the pod log and on the Import runs screen. A finished `Job` cannot be changed: delete it before the
   next command.

   | Command | clamd scanning | external scanning |
   |---|---|---|
   | `validate-mapping`, `extract` (read the source) | `jobs/importer-source-access` | `jobs/importer-external-scanning-source-access` |
   | `status`, `dry-run`, `load`, `reconcile`, `relink-owners`, `purge-staging` | `jobs/importer` | `jobs/importer-external-scanning` |

   Your own overlay can instead list `jobs/importer` (or the external variant) under `resources:` and
   `jobs/components/source-access` under `components:` for those two commands.
4. **Network.** `trailkeep-importer` admits no ingress and allows egress only to DNS, the database and clamd;
   `trailkeep-egress` excludes the importer pod, so it cannot reach the identity provider or the SMTP relay. Only
   `validate-mapping` and `extract` read the source: the `source-access` component labels their pod
   `trailkeep.io/source-egress: "true"`, which admits it to `trailkeep-importer-source`, in the same step that mounts
   the source password. Replace the placeholder addresses as in `trailkeep-egress`.
5. **Afterwards.** Lock `TRAILKEEP_IMPORT` again when the import is over. Keep `trailkeep-import-hash-key` while any
   delta run may still compare with the ledger.

## Runtime properties

- **Pod security.** Non-root, read-only root filesystem, no privilege escalation, all capabilities dropped, seccomp
  `RuntimeDefault`, no service-account token. On OpenShift this fits the default `restricted-v2` SCC, so no custom
  SCC is needed. `runAsUser` is not set, so the platform's UID range applies.
- **Scratch space.** `/tmp` (`java.io.tmpdir`), `/var/lib/trailkeep/uploads` (multipart) and
  `/var/lib/trailkeep/tomcat` (Tomcat base directory) are `emptyDir` volumes with size limits.
- **Probes.** On the management port 8081: `/actuator/health/liveness` and `/actuator/health/readiness`. Their
  members are listed explicitly, so cluster-level indicators never restart a healthy pod.
- **Metrics.** `/actuator/prometheus` on port 8081. The `NetworkPolicy` admits it only from cluster monitoring.
- **Logs.** One JSON object per line on stdout, with `requestId` (and later `userId`, `caseNumber`, `module`).
- **Batch profiles.** The purge `CronJob` and importer `Job` run the same image with `prod,purge` or
  `prod,importer` and `spring.main.web-application-type=none`.
- **Migrations.** Run as a separate pre-deploy `Job` with the schema-owner credential (added with the persistence
  work). The app itself only validates the schema.

## Alerts

`components/prometheus-rules/` is a Kustomize component with a `PrometheusRule` (it needs the Prometheus Operator, as
on OpenShift's cluster monitoring). Add it to an overlay with `components: [../../components/prometheus-rules]`, or
load its expressions into your own Prometheus. Each alert links to the section below. The System screen
(`/admin/system`, System Admins and Compliance Admins) shows the same facts: each job's last success, the outbox and
the virus scanner.

### A scheduled job is late

`TrailkeepSlaEvaluatorLate`, `TrailkeepRemindersLate`, `TrailkeepReportDeliveryLate`, `TrailkeepArchiveLate`: the
job has not succeeded on any replica within its health indicator's grace (30 minutes for the SLA evaluator, 2 hours
for the others). The jobs run on one replica at a time under ShedLock, so a single stuck pod does not cause this.
Check that at least one pod is running and can reach the database, read the pods' logs for the job's name, and check
the `SHEDLOCK` row for the job (a lock held past its `LOCK_UNTIL` by a pod that no longer exists is released when it
expires). The job resumes on the next schedule once the cause is gone; nothing has to be replayed by hand.

### Mail is not being sent

`TrailkeepMailNotSending` (every attempt for 15 minutes was a retry, none was sent) or `TrailkeepMailBacklog` (more
than 500 mails waiting for 30 minutes): the SMTP relay is unreachable or refusing mail. Check the egress
`NetworkPolicy` to the relay, `spring.mail.host` and the relay's own health. Mail keeps queuing in
`NOTIFICATION_OUTBOX` and is sent when the relay is back; setting `app.mail.enabled` to false stops attempts without
losing mail.

### Mail failed for good

`TrailkeepMailFailed`: a mail used up its attempts and is marked failed; it will not be retried. The System screen
shows the count. The row's `LAST_ERROR` says why (for example a recipient the relay rejects). Failed rows stay in
the outbox; the purge removes mail only together with the purged case or the expired report run it belongs to.

### The database pool is exhausted

`TrailkeepDbPoolExhausted`: on some replica, requests have been queuing for a connection from the HikariCP pool for
5 minutes (`hikaricp_connections_pending` above zero). A connection that is momentarily busy shows as a pending count
for milliseconds, so five minutes means the pool is too small for the load or connections are held too long. Check
`hikaricp_connections_active` against `hikaricp_connections_max` and the database's own sessions and locks; long CSV
exports and attachment downloads hold a connection while they stream (bounded by `app.export.max-rows` and
`app.attachments.max-concurrent-downloads`). Raise `spring.datasource.hikari.maximum-pool-size` only within what the
database allows for all replicas together.

### The virus scanner is down

`TrailkeepScannerDown`: clamd has not answered some replica's probe (a `VERSION` request every minute over the same TLS
connection as a scan, `trailkeep_scanner_up`) for 5 minutes. Uploads are refused while it is down, fail-closed; nothing
is stored unscanned. Check the ClamAV pod and its TLS sidecar, the `clamav-tls` and `clamav-ca` Secrets (an expired or
replaced certificate fails the handshake), and the egress `NetworkPolicy` from the replica that reports 0. The System
screen shows whether clamd answers right now and its version. With external scanning
(`overlays/external-scanning`) there is no probe and no `trailkeep_scanner_up` series, so this alert never fires.
