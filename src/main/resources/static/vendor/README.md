<!-- SPDX-License-Identifier: Apache-2.0 -->
# Vendored front-end assets

Served same-origin (CSP `script-src 'self'`, `style-src 'self'`), so no CDN and no SRI. Files are copied
unmodified from the npm registry tarballs; upgrade by replacing the file and updating this table.

| Asset | Version | License | Source | SHA-256 of the vendored file |
|---|---|---|---|---|
| `htmx/htmx.min.js` | htmx.org 2.0.11 | 0BSD (`htmx/LICENSE`) | `https://registry.npmjs.org/htmx.org/-/htmx.org-2.0.11.tgz`, `dist/htmx.min.js` | `d6fdc75f204e6bdefa99b69bf1e6d4ac69b8a364f77929f45c13476b4000f717` |
| `bootstrap/bootstrap.min.css` | bootstrap 5.3.8 | MIT (`bootstrap/LICENSE`) | `https://registry.npmjs.org/bootstrap/-/bootstrap-5.3.8.tgz`, `dist/css/bootstrap.min.css` | `d85327d99c7a3ee1f9b5d0500d1370acea3ad2db39c163c2f51f232baedbdede` |

Bootstrap's JavaScript and Popper are deliberately **not** vendored (ADR-0009): they set inline styles that the
strict `style-src` blocks. Interactive behaviour lives in `/js/app.js`.

The test-only `src/test/resources/axe/axe.min.js` (axe-core 4.14.0, MPL-2.0, unmodified) is never packaged.
