<!-- SPDX-License-Identifier: Apache-2.0 -->
# Portable migrations

Versioned Flyway scripts that run on **both** Oracle and PostgreSQL. Write vendor types only through the
placeholders `${id} ${int} ${bool} ${true} ${false} ${clob} ${bytes} ${tstz}` (BUILD_PLAN Appendix A).
`MigrationLintTest` checks every script here: identifiers ≤ 30 characters, no reserved words of either
vendor, no vendor types outside placeholders, no `VARCHAR` wider than 1000.

One versioned migration per pull request, shipped with its entities (`V1__identity` arrives in PR 6).

Test builds also see `src/test/resources/db/migration/common/V0_1__lint_probe.sql`, a test-only table that
proves the schema-validation harness; it is never packaged.
