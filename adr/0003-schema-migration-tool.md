<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0003: Use Flyway Community for migrations, with Liquibase as the pre-approved fallback

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #1; A.1 #5 (SQL kept portable)
- **Plan sections:** BUILD_PLAN.md §7.2 (Persistence), §8 rules 1–3, §19 (Phase 0 questions)

## Context and problem statement

Flyway Community support for older Oracle versions is limited by release, and the deploying organization's Oracle version (19c is the working assumption) is not confirmed until Phase 0. The tool choice must not become a rewrite if Phase 0 says no.

## Decision drivers

- Apache-2.0 licensing (Flyway Community with the `flyway-database-oracle` module).
- Support for the organization's real Oracle version.
- Switching tools must not require rewriting SQL.
- The Oracle test matrix must resemble the target.

## Considered options

1. Flyway Community only.
2. Liquibase only.
3. Flyway Community first, Liquibase pre-approved as fallback, SQL kept tool-neutral.

## Decision outcome

**Option 3.** Phase 0 confirms the Oracle version and exercises Flyway Community (with `flyway-database-oracle`) against it on day one. If it does not cover that version, Liquibase (Apache-2.0) is adopted without a further decision. Migration SQL stays tool-neutral: plain `.sql` files under `db/migration/{common,oracle,postgresql}` using the placeholders `${id} ${int} ${bool} ${clob} ${bytes} ${tstz}` and `NLS_LENGTH_SEMANTICS=CHAR` via `initSql`. The Oracle matrix runs `gvenzl/oracle-xe:21-slim` (closest public image to a 19c target: no native `BOOLEAN`, same string-size limits) and `gvenzl/oracle-free:23-slim`. The deploying organization runs `-Poracle-it` against its real version in Phase 1.

### Consequences

- **Good:** a known escape hatch; no vendor-specific SQL in the common folder.
- **Bad:** neither public Oracle image is 19c, so residual risk remains until the organization's own run. Tool-neutral SQL forgoes some tool-specific conveniences.

## Confirmation

- Migration-lint test over all scripts (identifiers ≤ 30 characters, reserved words, ANSI types outside placeholders, `VARCHAR` > 1000) (WP-03a).
- Oracle integration tests on xe 21 and free 23 on `main`, nightly and release tags (Part G).
- A migration failing on either vendor blocks the next merge (Part E).
