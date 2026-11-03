<!-- Title: conventional subject, e.g. "db: V4 case schema with CaseRecord entity (WP-11)" -->

## What

<!-- One paragraph. Which work package / Part D PR, and what this PR delivers. -->

## Plan deviations

<!-- "None", or what you did differently from IMPLEMENTATION_PLAN / BUILD_PLAN and why. If a decision changed, link the ADR. -->

## Checklist (every box is a real check — leave unchecked and explain if not applicable)

- [ ] Acceptance criteria of the work package are covered by tests in this PR
- [ ] `./mvnw verify` green locally (PostgreSQL integration tests included)
- [ ] Authorization: new mutating endpoints tested for allowed role, nearest disallowed role (403), unreadable case (404)
- [ ] Audit: every new state change writes an audit event, with a test
- [ ] Migration: one versioned migration at most, portable (lint passes), shipped with its entity change
- [ ] Accessibility: changed screens pass axe with 0 violations; keyboard-only walk-through recorded below
- [ ] No gate weakened (no `.disable()`, no suppressions without justification, no threshold changes, no skipped tests)
- [ ] No organization-specific data, hostnames, names, or secrets; seed data synthetic
- [ ] New dependencies listed below with reason and license
- [ ] SPDX header on every new file; commits DCO-signed (`-s`); ≤ 400 net production lines or size-exempt per plan
- [ ] Javadoc on module API changes; `CHANGELOG.md` entry under Unreleased

## Keyboard-only walk-through

<!-- For UI changes: the sequence of keys that completes the flow, and what the screen reader announced. "N/A" for non-UI PRs. -->

## New dependencies

<!-- groupId:artifactId — why — license. "None" otherwise. -->
