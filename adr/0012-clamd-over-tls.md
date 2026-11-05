<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0012: Reach clamd over server-authenticated TLS

- **Status:** Accepted
- **Date:** 2026-11-05
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #3 (attachment bytes, synchronous ClamAV scan), A.2 #21 (Tika and images)
- **Plan sections:** BUILD_PLAN.md §9 (Transport, Input validation), §11.1 (Antivirus), §16 (deployment, NetworkPolicy)

## Context and problem statement

Every upload is streamed to clamd (`INSTREAM`) before it is stored (A.1 #3). BUILD_PLAN §9 "Transport" says
"app → clamd (network policy; mTLS optional)", so the plan accepted plain TCP inside the cluster. The upload is
the file itself, and an attachment on a restricted case can carry PHI. The build's static-analysis gates flag the
plain socket the client needs: FindSecBugs `UNENCRYPTED_SOCKET` and Semgrep
`java.lang.security.audit.crypto.unencrypted-socket`. clamd has no TLS listener of its own.

## Decision drivers

- Upload bytes can carry PHI; on the wire they are protected by more than a NetworkPolicy.
- The static-analysis gates stay unweakened: no exclusion, no suppression, no rule edited (CLAUDE.md rule 3).
- Owner decision, 2026-11-05.

## Considered options

1. Plain TCP under the NetworkPolicy, as planned, with narrow gate exclusions for the one socket.
2. Server-authenticated TLS to a TLS-terminating sidecar in front of clamd; the NetworkPolicy still applies.
3. Mutual TLS, as option 2 plus a client certificate for the application.

## Decision outcome

Option 2. BUILD_PLAN §9 "Transport" for app → clamd reads, from this ADR on: **TLS required
(server-authenticated); network policy still applies; mTLS optional.**

- `ClamdClient` opens an `SSLSocket` from the Spring Boot SSL bundle named by `app.attachments.clamd.ssl-bundle`
  (`spring.ssl.bundle.*`), so trust material is configuration. TLS 1.3 and 1.2 only; endpoint identification
  (`HTTPS`) on, so the certificate must name the host the application connects to.
- There is no plain-socket path, no trust-all manager and no disabled hostname check anywhere, tests included. A
  handshake failure is "scanner unavailable" and the upload is refused, like any other failure to get a verdict.
- clamd listens on loopback in its pod; a TLS sidecar (ghostunnel) in the same pod terminates TLS on port 3311 and
  forwards to `localhost:3310`. Only 3311 is exposed. The server certificate comes from a Kubernetes Secret
  (`clamav-tls`, for example issued by cert-manager); the application trusts its CA through the bundle.
- mTLS (option 3) stays possible later: the bundle can carry a key store and the sidecar can require client
  certificates. It is not required now.

### Consequences

- **Good:** upload bytes are encrypted between the application and the scanner; the gates stay as they are; the
  server is authenticated, so a misrouted connection cannot return a forged clean verdict.
- **Bad:** one more container (the sidecar) and a certificate to issue and rotate for the ClamAV pod; the local
  compose stack generates a dev CA at start-up. A certificate that expires or stops matching the host refuses every
  upload until it is fixed, by design (fail closed).

## Confirmation

- SpotBugs/FindSecBugs and Semgrep (`p/java`) run unchanged and pass: there is no plain socket to flag.
- `ClamdClientTest`: TLS fakes with a run-time CA; an untrusted certificate and a certificate for another host are
  both "scanner unavailable".
- `ClamdClientIT`: real clamd behind the ghostunnel sidecar, with a run-time certificate; EICAR is quarantined.

## More information

- BUILD_PLAN.md §9 is not edited (CLAUDE.md rule 11); this ADR supersedes its app → clamd Transport line.
- WP-14 part 2 pull request, "Plan deviations".
