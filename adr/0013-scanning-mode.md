<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0013: Let the deployment choose who scans uploads, clamd by default

- **Status:** Accepted
- **Date:** 2026-11-05
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #3 (attachment bytes, synchronous ClamAV scan before persist), A.4 #37
  (migrations land with their entities)
- **Plan sections:** BUILD_PLAN.md §9 (Input validation: the antivirus rule), §11.1 (Antivirus), §16 (deployment);
  amends the WP-14 decision "synchronous ClamAV scan before persist"; ADR-0012 still governs `clamd` mode

## Context and problem statement

WP-14 and BUILD_PLAN §9 scan every upload with ClamAV, synchronously and fail-closed, before it is stored. Some
organizations already scan files in other ways: upstream of the application (a web gateway or an ICAP proxy in front
of it) or at the user endpoints. For them a second scanner costs a pod, a TLS sidecar, a certificate and signature
updates. The owner asked for a way to switch Trailkeep's ClamAV scanning on or off administratively. A switch that
turns off malware scanning is security-relevant, so where it lives and what a stored file then says about itself
matter.

## Decision drivers

- Owner request, 2026-11-05: an option to switch ClamAV scanning on or off, because some organizations scan upstream
  or at the endpoints.
- A compromised administrator account must not be able to switch scanning off.
- A change to the scanning posture is visible in change control.
- A file Trailkeep did not scan must never be presented or recorded as clean.
- Scanning stays the default; an unset or mistyped value must never turn it off.

## Considered options

1. Keep ClamAV mandatory (no option).
2. A runtime switch on an administration screen, stored in the database.
3. Deployment configuration, `app.attachments.scanning` = `clamd` (default) or `external`, read at startup.

## Decision outcome

Option 3, the owner's choice.

- **The property.** `app.attachments.scanning` takes `clamd`, the default, or `external`. It is set by whoever
  deploys (`APP_ATTACHMENTS_SCANNING` in the ConfigMap or the environment) and read at startup only. Changing it is
  a redeploy, so it goes through change control, and no account in the application, a Compliance or System Admin
  included, can switch scanning off. An unset value is `clamd`. An unknown value fails startup.
- **`clamd` mode** is unchanged: every upload is scanned synchronously before persist and refused without a verdict
  (fail-closed), over TLS per ADR-0012. The clamd host and the `clamd` SSL bundle are required.
- **`external` mode** is an explicit deployment choice for organizations that scan upstream or at endpoints.
  `ClamdClient` is not created (a conditional bean), so the clamd host, the SSL bundle, the ClamAV Deployment, its
  TLS sidecar, the `clamav-tls` and `clamav-ca` Secrets and the ClamAV NetworkPolicy are not needed
  (`deploy/overlays/external-scanning`). Every other intake check still runs: the temp file, the size cap, SHA-256,
  the Tika allow-list and extension cross-check, refusal of disguised executables, and the image header cap. Only
  the virus scan is skipped. There is no ClamAV call and no fallback to one.
- **An honest status.** Files stored in `external` mode get the scan status `NOT_SCANNED` ("not scanned by
  Trailkeep"), never `CLEAN`. `V4_4__attachment_not_scanned` widens `CK_ATTACHMENT_SCAN` to accept it, and
  `CK_ATTACHMENT_RELEASE` is unchanged. A file keeps the status it was stored with: switching the mode later does not
  relabel earlier files.
- **Download rule.** `NOT_SCANNED` files may be downloaded, because scanning was delegated. `QUARANTINED` files
  never are. A release applies only to `QUARANTINED`. `ScanStatus.isDownloadable()` encodes this.
- **Audit trail and visibility.** The upload's audit entry records the scan mode in force, which the intake carries
  as `ScannedUpload.scanMode()`. Wherever a file's status is shown, a `NOT_SCANNED` file reads "Not scanned by
  Trailkeep (scanned upstream)" (`tk.attachment.scan.NOT_SCANNED`). At startup the application logs the mode at INFO,
  or at WARN in `external` mode, naming the consequence.

BUILD_PLAN §9's antivirus rule and the WP-14 line "synchronous ClamAV scan before persist" read, from this ADR on:
**synchronous ClamAV scan before persist, unless the deployment sets `app.attachments.scanning=external`, in which
case files are stored as `NOT_SCANNED`.**

### Consequences

- **Good:** organizations with upstream or endpoint scanning run without a redundant scanner, sidecar and
  certificate. The default stays scan-everything and fail-closed. The status, the audit entry and the badge never
  claim a scan that did not happen. The switch sits outside the application's own authority, so taking over an
  account does not turn it off.
- **Bad:** in `external` mode Trailkeep relies entirely on the organization's scanning. A file that reaches the
  application by a path the upstream scanner does not cover is stored unscanned and is downloadable. This is the
  deploying organization's risk, and it accepts it by setting the property. Changing the mode needs a redeploy.

## Confirmation

- `ScanningModeTest`: the default is `clamd`; `external` binds; an unknown value fails startup; an `external` context
  starts without the clamd host, the SSL bundle or a `ClamdClient`; a `clamd` context still requires both; the
  startup log names the mode.
- `UploadIntakeTest`: in `external` mode a valid file is `NOT_SCANNED` and no scanner is called, and a disguised
  executable, the size cap and the image cap are still refused. The existing `clamd`-mode tests are unchanged.
- `CaseAttachmentTest`: `NOT_SCANNED` is downloadable and cannot be released; only `QUARANTINED` is withheld.
- `AbstractAttachmentContentIT` (PostgreSQL and Oracle): the database accepts `NOT_SCANNED` and refuses an unknown
  status.

## More information

- ADR-0012 (clamd over TLS) applies unchanged in `clamd` mode.
- BUILD_PLAN.md and IMPLEMENTATION_PLAN.md are not edited (CLAUDE.md rule 11). This ADR amends their antivirus rule,
  and the pull request lists it under "Plan deviations".
- `deploy/README.md`, "Scanning mode".
