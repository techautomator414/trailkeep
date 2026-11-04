<!-- SPDX-License-Identifier: Apache-2.0 -->
# Local Keycloak dev realm

`trailkeep-dev-realm.json` is imported by the `keycloak` service in `compose.yaml` and by `KeycloakLoginIT`.
Everything in it is synthetic and for local development only: the client secret `dev-only-client-secret` and
the password `dev-password` (every user) are deliberately obvious and are never used outside a laptop or CI.

| User | Intended role (BUILD_PLAN §14, IMPLEMENTATION_PLAN A.4 #39) |
|---|---|
| `alpha.reader`, `alpha.analyst`, `alpha.manager1`, `alpha.manager2` | Reader, Analyst and two Managers of team Alpha |
| `beta.reader`, `beta.analyst`, `beta.manager1`, `beta.manager2` | the same for team Beta |
| `compliance.admin` | Compliance Admin (group `trailkeep-compliance-admins`) |
| `system.admin` | System Admin (group `trailkeep-system-admins`) |
| `disabled.user` | disabled **in Trailkeep** (see below) |

- **Global roles** come from groups. The `groups` claim (group-membership mapper, short names) is mapped by
  `app.security.group-roles`.
- **Team roles** are never in the identity provider (BUILD_PLAN §3.1): they live in `APP_USER_TEAM`. The local
  seed that creates teams Alpha and Beta and assigns these memberships arrives with the admin work packages
  (WP-09/WP-10). Until then the user names document the intent.
- **`disabled.user`** is enabled in Keycloak on purpose. Disabling is the application's decision
  (`APP_USER.DISABLED`), and the login must reach Trailkeep for Trailkeep to refuse it. `KeycloakLoginIT` disables
  it in the database and proves the refusal.
- User ids are fixed UUIDs so tests can refer to a subject (`sub`) before the user's first login.
