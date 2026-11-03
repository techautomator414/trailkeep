<!-- SPDX-License-Identifier: Apache-2.0 -->
# ADR-0010: Reports ship as accessible HTML and CSV in v1; tagged PDF follows in v1.1

- **Status:** Accepted
- **Date:** 2026-11-03
- **Register rows:** IMPLEMENTATION_PLAN.md A.1 #13; Part C v1.1 backlog
- **Plan sections:** BUILD_PLAN.md §4.5, §7.2 (Reports, PDF export rows), §10.1 (Documents the system produces), §10.3

## Context and problem statement

Section 508 applies to documents the system produces. Hand-tagging PDF/UA with raw PDFBox is a multi-week subsystem and veraPDF is unforgiving. Shipping a PDF that fails conformance would violate the accessibility gate.

## Decision drivers

- Every shipped format must be accessible by construction.
- Do not take on an unbounded subsystem in v1.
- A non-conformant file must never be delivered.

## Considered options

1. Hand-tagged PDF via the PDFBox structure-tree API in v1.
2. PDF in v1 without conformance verification.
3. HTML + CSV in v1; PDF via `openhtmltopdf` with a veraPDF gate in v1.1.

## Decision outcome

**Option 3.** v1 ships an accessible HTML report view with a print stylesheet, plus CSV (Apache Commons CSV with formula-injection escaping: cells beginning `= + - @` are prefixed). **v1 ships no PDF.** In v1.1 the same accessible HTML is rendered once into tagged PDF (PDF/UA-1, ISO 14289) with the maintained `openhtmltopdf` fork over PDFBox. The v1.1 pipeline has document title, language, heading and table structure, and no untagged content. veraPDF gates every generated file in CI, and if a conformant PDF cannot be produced for a report, the export is withheld rather than shipped non-conformant. `openhtmltopdf` is LGPL-2.1, dynamically linked, the one LGPL runtime dependency, to be recorded in `NOTICE`.

### Consequences

- **Good:** v1 formats are accessible by construction; no PDF subsystem risk on the critical path.
- **Bad:** users who want PDF must print the HTML view until v1.1; the v1.1 work adds an LGPL dependency.

## Confirmation

- WP-22 acceptance: report output matches the hand-computed fixture; axe clean on the HTML report.
- CSV formula-escaping test (WP-15, WP-22).
- veraPDF check is scheduled for v1.1 only; it runs nightly and on release tags (Part G).
- License allow-list report flags any new LGPL artifact at v1.1 adoption.
