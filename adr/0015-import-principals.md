<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0015: Two seeded, disabled principals act for the importer

- **Status:** Proposed
- **Date:** 2026-11-10
- **Register rows:** IMPLEMENTATION_PLAN.md A.4 #37 (migrations land with their entities)
- **Plan sections:** BUILD_PLAN.md §5.1 (`APP_USER`: users come from the identity provider), §3.2 (owner ≠ approver),
  §15 (importing records); docs/design/importer.md §7.1 and §7.3 (R7); ADR-0014

## Context and problem statement

Imported records need actors. These columns are all foreign keys to `APP_USER`:

- on `CASE_RECORD`: `CREATED_BY`, `UPDATED_BY` and `OWNER_ID`;
- on `CASE_ATTACHMENT`: `UPLOADED_BY`;
- on `CLOSURE_REQUEST`: `REQUESTED_BY`, `APPROVER_ID` and `DECIDED_BY`.

There is no system user. Every `APP_USER` row comes from the identity provider at first sign-in.

The owner decided on 2026-11-05 that two non-login principals act for the importer: "Trailkeep import" and "Legacy
approval (imported)" (design §7.3). These are users that do not come from the identity provider, so three questions
need a recorded answer:

- how they are created;
- how they are kept from signing in or holding access;
- how the code finds them.

## Decision drivers

- No person may sign in as a principal, and a principal may never read or act on a case.
- An imported closure must satisfy `CK_CLOSURE_SOD` (`REQUESTED_BY <> APPROVER_ID`) without naming a real person as the
  decider of a closure made years before.
- The rows must exist on both vendors before any import, without the import user needing INSERT on `APP_USER`.
- An administrator must not be able to turn a principal into a user by mistake.

## Considered options

1. The run's approver as creator, as owner of unmatched closed cases and as decider of imported closures.
2. One "Trailkeep import" principal for everything.
3. Two principals, seeded by a versioned migration.
4. Two principals, created idempotently by a `user` service call when the importer starts.

## Decision outcome

Option 3.

- **The rows.** `V11__import_principals` inserts two `APP_USER` rows:
  - **Ids:** fixed and negative, `-1001` "Trailkeep import" and `-1002` "Legacy approval (imported)", named once as
    `ImportPeopleService.IMPORTER_ID` and `LEGACY_APPROVAL_ID`. `SEQ_APP_USER` starts at 1, so it can never issue them.
  - **Outside any sentinel:** several controllers read a malformed id parameter as `-1`, which is safe only while no
    row has that id. The principals keep clear of it, so a malformed value can never be mistaken for a principal.
  - **State:** `DISABLED`, with no team and no global role.
  - **External ids:** reserved, under `urn:trailkeep:principal:`. They are not subjects any identity provider issues.
  - **Email:** `.invalid` addresses (RFC 2606), so no mail can reach them.
- **No sign-in, no access.** Sign-in already refuses a disabled user and writes nothing else (`UserSignInServiceImpl`).
  With no roles, `CaseScope` grants them nothing.
- **Kept that way.** `user`'s admin services treat a principal as an unknown user. The user list leaves the principals
  out. Enabling or disabling one answers 404, and so does giving one a team role.
- **Found by name, not by number.** `ImportPeopleService.principals()` looks both up by external id. No other code
  hard-codes their ids.

### Consequences

- Good: an imported closure's requester and decider differ without either being a person.
- Good: no person owns an unmatched closed case, restricted ones included.
- Good: the import user needs only SELECT on `APP_USER` (ADR-0014, design §5).
- Bad: `APP_USER` holds two rows that are not people. Every administrative list of users must keep excluding them.
  The user list does, and `ImportPeopleIT` checks it.
- Neutral: options 1 and 2 are rejected by the owner's decision (design §7.3):
  - Option 1 makes a real person the owner of restricted cases and the recorded decider of historical closures.
  - Option 2 cannot satisfy `CK_CLOSURE_SOD` without a person.
- Neutral: option 4 would give the import user INSERT on `APP_USER` and create the rows only once an import runs.

## Confirmation

`ImportPeopleIT` and `OracleImportPeopleIT` check that:

- both rows exist, disabled, with no role and no mail address;
- they are left out of the user list;
- enabling or disabling one, and giving one a team role, answers not found;
- signing in with a principal's subject is refused and changes nothing;
- people lookups never return a principal.

`ClosureControllerIT` and `UpdateRequestControllerIT` check that the `-1` sentinel and both principals' ids are still
refused with 422 as a closure approver and as an update-request recipient.
