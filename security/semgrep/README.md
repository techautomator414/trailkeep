<!-- SPDX-License-Identifier: Apache-2.0 -->
# Custom Semgrep rules

Project-specific rules that back IMPLEMENTATION_PLAN Part B. Each `<rule>.yaml` has a test file with the same base
name whose `ruleid:` / `ok:` comments state where the rule must and must not fire.

| Rule | Bans |
|---|---|
| `thymeleaf-unescaped-output` | `th:utext`, `data-th-utext` and `[(...)]` in templates; `tk:safe` is the only unescaped path |
| `spring-security-protection-disabled` | `.disable()` on CSRF, headers, frame options, CSP, HSTS, content-type options, cache control, referrer policy and the cross-origin headers |
| `htmx-inline-script` | `hx-on*` attributes, `js:`/`javascript:` values in `hx-vals`/`hx-headers`, and `hx-vars` |
| `jpa-dynamic-query-string` | `createQuery`/`createNativeQuery` (and Hibernate's selection/mutation variants) with a non-constant query string |

```bash
pip install semgrep==1.183.0
semgrep --test --metrics=off security/semgrep/                 # rule tests
semgrep scan --metrics=off --error --config security/semgrep/ src/   # the real code: no findings
```

The test files hold planted violations; CI runs them through `--test` and excludes `security/semgrep` from the
repository-wide scan (`.github/workflows/ci.yml`).
