-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.5 SEARCH_TEXT is stored lower-cased (ADR-0006, amended 2026-11-06), so the search applies LIKE to the column
-- without calling a function on the CLOB, which Hibernate refuses on Oracle. Every writer now lower-cases; this
-- statement brings rows written before that (developer databases; no release carries V4 yet) in line. LOWER accepts
-- text and CLOB alike, so the statement is the same on PostgreSQL and Oracle.
UPDATE CASE_SEARCH SET SEARCH_TEXT = LOWER(SEARCH_TEXT);
