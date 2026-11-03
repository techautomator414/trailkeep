# Trailkeep — instructions for Claude Code sessions

This repository is **public** and **Apache-2.0**. Every session, cloud or local, follows these rules. They are
short because the real specification lives in two documents you must read before writing code:

- `IMPLEMENTATION_PLAN.md` — **Part B** (engineering standards; the code is judged against it), **Part C**
  (work packages with acceptance criteria), **Part D** (PR order), **Part E** (definition of done),
  **Part G** (which CI check runs where). Where it disagrees with `BUILD_PLAN.md`, it wins.
- `BUILD_PLAN.md` — what the product is: flows (§4), domain model (§5), SLA design (§6), architecture (§7),
  Oracle/PostgreSQL portability rules (§8), security (§9), accessibility (§10), Appendix A DDL sketch.

## Working agreement

1. **One work package (or one Part D PR) per session**, on a branch named `pr-NN-short-name` or
   `wp-NN-short-name`. Finish with: `./mvnw verify` green locally, push, open a **draft** pull request using
   `.github/PULL_REQUEST_TEMPLATE.md`, and report back if you were asked to.
2. **Read the acceptance criteria for your WP first** and make the tests that prove them part of the PR.
3. **Never weaken a gate to get green.** No `.disable()` on Spring Security, no `@SuppressWarnings` or
   `@SuppressFBWarnings` without a justification string, no lowering JaCoCo thresholds, no skipping tests,
   no `-Dmaven.test.skip`, no editing Semgrep/ArchUnit rules to pass. If a gate is wrong, say so in the PR
   and leave the gate alone.
4. **Commits are DCO-signed** (`git commit -s`), conventional subjects (`build:`, `db:`, `security:`,
   `web:`, `audit:`, `ops:`, `feat:`, `fix:`, `test:`, `docs:`), ≤ 400 net production lines per PR unless the
   plan marks the PR size-exempt. One versioned migration per PR, shipped with its entity change.
5. **Nothing organization-specific, ever**: no real data, hostnames, identity-provider details, people's
   names, or the name of any legacy tool this project replaces. Seed data is synthetic. Stakeholder input is
   paraphrased. If a value must vary per deployment, it is configuration under `app.*`.
6. **No secrets in the repo** — not in tests, compose files or workflows. Dev credentials live only in
   `compose.yaml` and the Keycloak dev realm and are obviously fake.
7. **Persistence rules are not negotiable** (Part B "Persistence", BUILD_PLAN §8): no `@Lob`, no `byte[]`
   on entities, `@Enumerated(EnumType.STRING)`, `open-in-view=false`, migrations own the schema, native SQL
   only in `*JdbcDao` classes with a comment saying why.
8. **Access control goes through `CaseScope`** (Part A #6). Case-rooted repositories extend `Repository`,
   take a scope on every query method, and are injected only into `@CaseAccessPath` classes. Unreadable
   cases answer 404.
9. **Accessibility is a gate**: every new or changed screen passes axe with 0 violations; semantic HTML,
   labels, focus management per BUILD_PLAN §10.1; native `<dialog>`; Bootstrap CSS only, no Bootstrap JS.
10. **Local environment**: `docker compose up -d` gives PostgreSQL, Keycloak, Mailpit and ClamAV. Oracle
    runs in CI on `main` (`-Poracle-it`), not locally, unless you are explicitly working on a vendor issue.
11. **Do not rewrite the plans.** If you discover the plan is wrong, record it in the PR description under
    "Plan deviations" and, if it changes a decision, add an ADR in `adr/` — do not silently diverge.
12. **Ask by leaving a note, not by stopping**: if something is ambiguous, make the smaller, reversible
    choice, state it in the PR, and continue.

## Layout you will create and respect

```
src/main/java/io/trailkeep/{shared,security,audit,user,casefile,closure,updaterequest,sla,document,notification,report,retention,importer,web}
src/main/resources/db/migration/{common,oracle,postgresql}
src/test/java/io/trailkeep/{architecture,e2e,...}
adr/  security/  accessibility/  deploy/  .github/
```

Base package `io.trailkeep`. Application class `TrailkeepApplication`. Module boundaries are verified by
Spring Modulith; see Part B "Layering and modules".
