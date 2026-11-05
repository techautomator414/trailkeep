<!-- SPDX-License-Identifier: Apache-2.0 -->
# Architecture Decision Records

Format: [MADR](https://adr.github.io/madr/); copy [`0000-adr-template.md`](0000-adr-template.md). Each ADR records rows of the decisions register in `IMPLEMENTATION_PLAN.md` Part A and names the check that enforces it. If a Part A decision changes, add a superseding ADR; do not edit an accepted one.

| ADR | Title | Status | Date |
|---|---|---|---|
| [0001](0001-spring-boot-4-only.md) | Target Spring Boot 4.x only, with Java 21 as the compile target | Accepted | 2026-11-03 |
| [0002](0002-modular-monolith-spring-modulith.md) | Build a modular monolith verified by Spring Modulith; `shared` owns cross-cutting contracts | Accepted | 2026-11-03 |
| [0003](0003-schema-migration-tool.md) | Use Flyway Community for migrations, with Liquibase as the pre-approved fallback | Accepted | 2026-11-03 |
| [0004](0004-two-vendor-persistence-rules.md) | Persistence rules that hold on both Oracle and PostgreSQL | Accepted | 2026-11-03 |
| [0005](0005-casescope-access-contract.md) | Enforce case visibility through a `CaseScope` contract in `shared` | Accepted | 2026-11-03 |
| [0006](0006-search-gateway.md) | Search behind `SearchGateway` with an atomic `CASE_SEARCH` side table | Accepted | 2026-11-03 |
| [0007](0007-sla-state-and-evaluation.md) | Keep SLA state in `CASE_SLA`, with one anchor rule and chunked evaluation | Accepted | 2026-11-03 |
| [0008](0008-sessions-and-oidc-logout.md) | JDBC sessions without persisted authorities, with a custom OIDC session registry | Accepted | 2026-11-03 |
| [0009](0009-server-rendered-ui-csp.md) | Server-rendered UI with Thymeleaf, htmx and Bootstrap CSS only, under an exact CSP | Accepted | 2026-11-03 |
| [0010](0010-reports-html-csv-v1-pdf-v1_1.md) | Reports ship as accessible HTML and CSV in v1; tagged PDF follows in v1.1 | Accepted | 2026-11-03 |
| [0011](0011-case-configuration-owned-by-casefile.md) | Case configuration (SLA policies, case types, outcomes) is owned by `casefile` | Accepted | 2026-11-04 |
| [0012](0012-clamd-over-tls.md) | Reach clamd over server-authenticated TLS | Accepted | 2026-11-05 |
