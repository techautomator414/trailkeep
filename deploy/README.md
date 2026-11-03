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
3. **NetworkPolicy targets** in `trailkeep-egress`: database, identity provider, SMTP relay and clamd. The base uses
   RFC 5737 documentation addresses.
4. **Secrets.** Create them from the platform's secret store; the manifests only reference them. Each key is a file
   named after the Spring property it sets (Spring `configtree`), mounted at `/etc/trailkeep/secrets/`:

   | Secret | Mounted by | Keys (added by the work packages that need them) |
   |---|---|---|
   | `trailkeep-web` | web `Deployment`, importer `Job` | `spring.datasource.username`, `spring.datasource.password`, OIDC client secret, SMTP password, audit HMAC key |
   | `trailkeep-purge` | purge `CronJob` only | the `TRAILKEEP_PURGE` database credential |

5. **Purge schedule.** Set `suspend: false` on `trailkeep-purge` once retention is enabled.

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
