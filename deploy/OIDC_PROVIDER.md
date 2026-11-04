<!--
  SPDX-License-Identifier: Apache-2.0
  Copyright 2026 Charlie Genova and contributors
-->
# Connecting Trailkeep to an OIDC provider: setup checklist

Vendor-neutral. Use it for the first real sign-in in a DEV environment and again for each later environment. It
holds no provider-specific or site-specific values; keep yours in your own overlay and secret store. The only
provider that appears here is the Keycloak dev realm that ships for local development
(`deploy/local/keycloak/`).

Trailkeep is OIDC-only: it stores no passwords and has no local accounts (BUILD_PLAN §9). The registration id is
fixed at **`idp`**, and every path below contains it. `{base}` means the externally visible origin of the
application, for example `https://trailkeep.example.org`, with no path.

Source of each statement: `src/main/resources/application.yml` (`spring.security.oauth2.client`),
`application-prod.yml`, `io.trailkeep.security.SecurityConfig`, `TrailkeepOidcUserService`, `SecurityProperties`,
`HtmxOidcLogoutSuccessHandler`, and ADR-0008.

## 1. Prerequisites

- [ ] The application is served over **HTTPS** at a stable `{base}`. The `SESSION` cookie is `Secure` in every
      profile, so a plain-HTTP deployment cannot keep a session (`http://localhost` is the browser exception).
- [ ] `APP_PROXY_PATTERN` (`app.proxy.pattern`) matches your router, so `X-Forwarded-Proto` and `X-Forwarded-Host`
      are honoured (see `deploy/README.md`). The redirect URI is built from the request (`{baseUrl}`); if the
      proxy settings are wrong, Trailkeep sends the provider a redirect URI with the wrong scheme or host and the
      provider rejects it.
- [ ] The pods can reach the provider's issuer URL and its discovery document at startup (add the provider to the
      egress `NetworkPolicy` target, `deploy/README.md` step 3), and the provider can reach `{base}` for the
      back-channel call (section 3).
- [ ] The provider's certificate chain is trusted by the JVM in the image, or its CA is added by your overlay.

## 2. Register the client at the provider

- [ ] Create one OpenID Connect client for this environment (do not share a client between DEV and production).
- [ ] **Confidential** client (the provider issues a client secret). The dev realm client is `publicClient: false`.
- [ ] Flow: **authorization code** only. The dev realm enables the standard flow and disables direct access
      grants, implicit and service accounts; do the same. No other grant is needed.
- [ ] Client authentication: Trailkeep configures `authorization-grant-type: authorization_code` and the client
      id and secret, and leaves Spring Security's default client authentication (HTTP Basic,
      `client_secret_basic`) in place. If your provider only offers another method, that is a code change, not
      configuration; record it on issue #30.
- [ ] **PKCE.** The repository does not set PKCE explicitly, and Spring Security's default sends a PKCE challenge
      for public clients only, so a confidential client as configured here does not send one. If your provider
      requires PKCE for confidential clients, that is a provider policy to relax or a code change to make; record
      it on issue #30 rather than working around it.
- [ ] Record the issuer URL (`iss` of the discovery document). Trailkeep finds the endpoints by issuer discovery
      (`spring.security.oauth2.client.provider.idp.issuer-uri` in the `prod` and `local` profiles).

## 3. URLs to register

All three are fixed by the code; none is configurable.

| Purpose | Value to register | Where it comes from |
|---|---|---|
| Valid **redirect URI** (authorization code callback) | `{base}/login/oauth2/code/idp` | `spring.security.oauth2.client.registration.idp.redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"` |
| Valid **post-logout redirect URI** (RP-initiated logout) | `{base}/` | `HtmxOidcLogoutSuccessHandler`: `setPostLogoutRedirectUri("{baseUrl}/")`. The dev realm allows `http://localhost:8080/*`; register the exact URL if your provider supports exact matching. |
| **Back-channel logout URL** | `{base}/logout/connect/back-channel/idp` | Spring Security's `oidcLogout` back-channel endpoint, enabled in `SecurityConfig.applicationChain`; the dev realm's `backchannel.logout.url` has this path |

Back-channel logout settings at the provider:

- [ ] Back-channel logout enabled, with the URL above.
- [ ] **Session required** (the logout token carries `sid`), as in the dev realm
      (`backchannel.logout.session.required: true`). Trailkeep also accepts a token with only `sub`, but
      `sid` is the precise form; without `sid` the logout ends every session of that `sub` (ADR-0008).
- [ ] The provider can POST to `{base}/logout/connect/back-channel/idp` from where it runs, and the route and
      network policies allow it. This endpoint is called by the provider, not by a browser.
- [ ] Front-channel logout is not used (the dev realm sets `frontchannelLogout: false`).

How a back-channel logout ends a session is internal (the pod POSTs its own `/logout` on loopback using the cookie
and CSRF header captured at login, ADR-0008), so no extra deployer setting is needed for it.

## 4. Scopes and claims

Requested scopes (fixed in `application.yml`): `openid`, `profile`, `email`. Configure the client to release them.

| Claim | Required | Used for |
|---|---|---|
| `sub` | Yes | The user's stable identity (`APP_USER.EXTERNAL_ID`). Must not change for a person. A login without it is refused. |
| `email` | Yes | Stored on the user. A login without a non-blank `email` is refused (error `missing_claims`). |
| `name` | No | Display name; falls back to `preferred_username`, then to `sub`. |
| `preferred_username` | No | Display name fallback only. |
| groups claim | No | Global roles only (section 5). The claim may be in the ID token or the userinfo response. It must be a JSON array of strings; a single string value is not read. |

- [ ] `sub` is a stable, non-reassigned identifier (not an email address that can be recycled).
- [ ] Do not rely on the provider for **team roles**: Reader, Analyst and Manager are managed inside Trailkeep
      at `/admin/teams` (BUILD_PLAN §3.1). A new user can sign in and will have no team role until a System
      Admin grants one.

## 5. Map groups to global roles

Two global roles exist, `COMPLIANCE_ADMIN` and `SYSTEM_ADMIN` (`io.trailkeep.user.GlobalRole`). A group name that is
not listed grants nothing.

- [ ] Choose or create two groups at the provider, one per role (names are yours; the dev realm uses
      `trailkeep-compliance-admins` and `trailkeep-system-admins`).
- [ ] Release the group-membership claim to the client as an array of **short group names** (no path prefixes unless
      you map the prefixed names).
- [ ] If the claim is not called `groups`, set `app.security.groups-claim` (default `groups`).
- [ ] Set `app.security.group-roles` as a map from the exact group string to the role:

  ```yaml
  app:
    security:
      groups-claim: groups
      group-roles:
        your-compliance-group: COMPLIANCE_ADMIN
        your-system-group: SYSTEM_ADMIN
  ```

  Put this in your overlay's configuration (a mounted Spring config file such as a ConfigMap-backed
  `application-*.yml`, loaded with the standard `spring.config.additional-location`), not in this repository.
  Group names contain characters that are awkward in environment-variable names, so a YAML file is the
  reliable route.
- [ ] A `SYSTEM_ADMIN` claim is not applied to a user who already holds team roles; the conflict is recorded as
      `ROLE_CONFLICT` in the audit trail. Keep system administrators out of team assignments.
- [ ] Global roles are recomputed at each sign-in. A role change at the provider takes effect at the user's next
      login, not mid-session.

## 6. Configuration keys

Set these in the overlay. Nothing here goes in the repository; the dev values in `application-local.yml` are
obviously fake and are for the `local` profile only.

| Key | Required | Value | Where |
|---|---|---|---|
| `app.security.oidc.issuer-uri` | Yes (prod) | Issuer URL from section 2. Feeds `spring.security.oauth2.client.provider.idp.issuer-uri` in `application-prod.yml`. | ConfigMap, env var `APP_SECURITY_OIDC_ISSUER_URI` |
| `app.security.oidc.client-id` | Yes | The client id. Feeds `spring.security.oauth2.client.registration.idp.client-id`. | ConfigMap, env var `APP_SECURITY_OIDC_CLIENT_ID` |
| `app.security.oidc.client-secret` | Yes | The client secret. Feeds `spring.security.oauth2.client.registration.idp.client-secret`. | **Secret store only** (section 7) |
| `app.security.groups-claim` | If not `groups` | Name of the claim listing groups. | ConfigMap or config file |
| `app.security.group-roles` | For admins | Group name to `COMPLIANCE_ADMIN` or `SYSTEM_ADMIN` (section 5). | Config file |
| `app.security.absolute-session-lifetime` | No | Default `8h`. How long a login lasts however active the user is. | ConfigMap |
| `server.servlet.session.timeout` | No | Default `30m`, the idle timeout; the expiry warning in the page reads it. | ConfigMap |

The `spring.security.oauth2.client.*` keys are already set in `application.yml` and `application-prod.yml` and need
no override in a normal deployment:

| Key | Fixed value |
|---|---|
| `spring.security.oauth2.client.registration.idp.provider` | `idp` |
| `spring.security.oauth2.client.registration.idp.authorization-grant-type` | `authorization_code` |
| `spring.security.oauth2.client.registration.idp.redirect-uri` | `{baseUrl}/login/oauth2/code/{registrationId}` |
| `spring.security.oauth2.client.registration.idp.scope` | `openid, profile, email` |
| `spring.security.oauth2.client.provider.idp.issuer-uri` | `${app.security.oidc.issuer-uri}` (`prod` profile) |

Do not set the `prod`-profile `issuer-uri` and `client-secret` keys to literals in a file that is committed anywhere.

## 7. Where the secret goes

- [ ] The client secret is a **file** in the platform secret store, mounted into
      `/etc/trailkeep/secrets/` (Spring `configtree`, `SPRING_CONFIG_IMPORT` in `deploy/base/configmap.yaml`). The file name is the
      property name, so the file is named `app.security.oidc.client-secret` and contains only the secret. It
      belongs to the `trailkeep-web` secret (`deploy/README.md` step 4).
- [ ] Never in the repository, a ConfigMap, an image, a workflow, a compose file, or a command line. The
      `dev-only-client-secret` value in the dev realm is a public, throwaway example and must never be reused.
- [ ] Rotating it is: new secret at the provider, update the secret store, restart the pods.
- [ ] The issuer URL and client id are not secrets and may sit in the ConfigMap, but keep them out of the public
      repository too if they identify your organization.

## 8. Verify

Use a non-production user and have database access for the last check. Run each check with the browser's network
panel or the provider's logs to hand.

1. **Startup.** The application starts and `/actuator/health/readiness` on port 8081 is `UP`. A startup failure
   naming the issuer means the pod cannot reach or trust the provider (section 1).
2. **Sign in.** Open `{base}/`. You are redirected to the provider, authenticate, and return to Trailkeep as that
   user (the dashboard greets you by display name). A `redirect_uri` error at the provider means section 3 or the
   proxy pattern is wrong.
3. **Claims.** The display name and email are right. If a group is mapped, the page shows the screens that role
   allows: `SYSTEM_ADMIN` reaches `/admin/teams`; `COMPLIANCE_ADMIN` reaches `/admin/sla-policies` and
   `/admin/case-types`. A user in no mapped group reaches neither. The audit trail has a `LOGIN` event for the sign-in.
4. **Sign out.** Use "Sign out". You land back on `{base}/` and the provider session is ended too: opening `{base}/`
   again asks for authentication instead of signing you straight in.
5. **Back-channel logout ends the session.** Sign in, keep the tab open, then end that user's session at the
   provider (its admin console or "sign out everywhere"). Within a few seconds the next request in the Trailkeep tab
   redirects to the provider. If it does not, check that the provider reached `{base}/logout/connect/back-channel/idp`
   (route, firewall, TLS) and the pod logs for a rejected logout token. With two replicas, repeat until the request
   lands on each pod; the session store is shared so the result must be the same.
6. **A disabled user is refused.** Disabling is Trailkeep's decision and is held in `APP_USER.DISABLED`; there is no
   admin action for it yet (issue #31). In DEV, with the database owner's permission, set `DISABLED = TRUE` for a
   test user (find the row by `EXTERNAL_ID`, which is the `sub`) and sign in as them. They authenticate at the
   provider, then land on `/login?error` in Trailkeep, no session is created, `LAST_LOGIN_AT` is not updated, and a `LOGIN_REFUSED` audit event is written.
   Re-enable the user afterwards. This is the same check `KeycloakLoginIT` runs against the dev realm. Note that a
   user disabled while signed in keeps the existing session until it ends (#31).
7. **Idle and absolute limits** (optional): the session warning appears two minutes before the idle timeout, and a
   login older than `app.security.absolute-session-lifetime` is ended.

## 9. Record the outcome on issue #30 (not the values)

When the checks pass, or when one fails, comment on issue #30 with:

- the date and the environment name in generic terms ("DEV"), and the build or image tag tested;
- pass or fail for each of the seven checks in section 8, and for failures the symptom;
- any code-level gap found (client authentication method, PKCE, claim shape);
- the claim names released and the group-to-role *shape* (how many groups, which roles), not the group names.

Do not paste the issuer URL, client id, client secret, hostnames, group names, user names or tokens into the issue,
the PR or any log you attach. The values stay in the overlay and the secret store.
