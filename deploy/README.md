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
| `jobs/importer-job.yaml` | One-off importer `Job`, applied by hand because applying a `Job` runs it. |

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
   **`APP_DB_APP_USER`** and **`APP_DB_PURGE_USER`**: the existing database users that the vendor `R__grants.sql`
   grants to (runtime and retention purge). The app does not start without them.
3. **NetworkPolicy targets** in `trailkeep-egress`: database, identity provider, SMTP relay and clamd. The base uses
   RFC 5737 documentation addresses.
4. **Secrets.** Create them from the platform's secret store; the manifests only reference them. Each key is a file
   named after the Spring property it sets (Spring `configtree`), mounted at `/etc/trailkeep/secrets/`:

   | Secret | Mounted by | Keys (added by the work packages that need them) |
   |---|---|---|
   | `trailkeep-web` | web `Deployment`, importer `Job` | `spring.datasource.username`, `spring.datasource.password`, OIDC client secret, SMTP password, `app.audit.hmac-key` (Base64, ≥ 32 random bytes; the `prod` profile refuses to start without it) |
   | `trailkeep-purge` | purge `CronJob` only | `spring.datasource.username`, `spring.datasource.password`: the `TRAILKEEP_PURGE` credential. Nothing else: the purge registers no OIDC client and writes no audit row, so it needs neither the client secret nor `app.audit.hmac-key` |
   | `clamav-tls` | ClamAV `Deployment` (TLS sidecar) only | `tls.crt`, `tls.key`: the sidecar's server certificate, naming the host in `APP_ATTACHMENTS_CLAMD_HOST` (the `clamav` Service by default); for example a cert-manager `Certificate` with `secretName: clamav-tls` (ADR-0012) |
   | `clamav-ca` | web `Deployment`, importer `Job`, purge `CronJob` | `ca.crt` only: the CA that issued `clamav-tls`, loaded as the `clamd` SSL bundle; never the key |

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

The `clamav-tls` and `clamav-ca` Secrets are then not created. For the importer `Job`, which is applied by hand,
delete its `clamav-ca` volume and volume mount. Make sure every path into the application passes your scanner: a
file that bypasses it is stored unscanned and can be downloaded.

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
