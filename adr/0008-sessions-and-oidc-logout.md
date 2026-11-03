<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0008: JDBC sessions without persisted authorities, with a custom OIDC session registry

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #9 (amended r3); A.2 #18 (disable purges sessions); A.5 #42
- **Plan sections:** BUILD_PLAN.md §9 (Identity)

## Context and problem statement

Replicas must share sessions, a disabled user must lose access immediately, and an IdP back-channel logout must end the session on every replica. `OidcBackChannelLogoutHandler` ends a session by POSTing the app's own `/logout` with the session cookie and the CSRF header captured at login. The default in-memory registry cannot span replicas, and a registry table with no place to keep the CSRF header cannot drive the handler. The handler's default cookie name is `JSESSIONID`, not Spring Session's `SESSION`.

## Decision drivers

- Role changes and disables take effect on the next request.
- No JDK serialization of security objects.
- Logout works across replicas.
- Scope: step-up re-authentication is not needed to ship v1.

## Considered options

1. Sticky sessions with in-memory registry.
2. Persist full authentication including authorities.
3. Spring Session JDBC with JSON attributes, permissions rebuilt per request, and a custom JDBC-backed OIDC registry.

## Decision outcome

**Option 3.**

- **Spring Session JDBC** with vendor schemas shipped as migrations, the vendor customizer, and JSON attribute serialization using Spring Security's Jackson mixins.
- **Authorities are not persisted.** Permissions are rebuilt per request from the database. Disabling a user deletes their sessions and refuses their next login.
- **No authorized-client store.** RP-initiated logout uses the ID token from the shared `OidcUser`.
- **Back-channel logout** through a custom **`JdbcOidcSessionRegistry`** over `OIDC_SESSION (SID PK, SESSION_ID, PRINCIPAL, AUTHORITIES_JSON)`. The last column carries the CSRF header map so `OidcSessionInformation` can be rebuilt. `OidcBackChannelLogoutHandler` is configured with `sessionCookieName("SESSION")` and a `logoutUri` on the pod's loopback. A daily cleanup removes `OIDC_SESSION` rows whose session no longer exists.
- Idle timeout 30 minutes with a 2-minute accessible warning and `/session/extend`; **absolute 8-hour lifetime** by filter from a login-time attribute.
- **Step-up re-authentication is cut from v1**; admin actions are audited instead. The mechanism (`max_age` plus an `auth_time` check) remains available.

### Consequences

- **Good:** no stale privileges in sessions; logout is uniform across replicas; no secrets or tokens stored beyond the session.
- **Bad:** custom registry code to maintain against upstream changes; an extra DB read per request to rebuild permissions; admin actions have no re-auth check in v1.

## Confirmation

- WP-04b acceptance: two app instances share a session; a back-channel logout POST to the loopback returns 2xx/3xx (not 403) and ends the session on both.
- Lifetime-filter and idle-timeout tests; disable ⇒ session purge test (WP-04b, WP-09).
- 90 % line coverage gate for the `security` package (Part B).
- Grants test: the app user may DELETE from `SPRING_SESSION*` and `OIDC_SESSION` (WP-03b).
