-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V21 search index, PostgreSQL (WP-30 part a2, #299; ADR-0006, amended 2026-11-23). A trigram index on
-- CASE_SEARCH.SEARCH_TEXT: the LIKE gateway's '%word%' patterns, unchanged, are answered from it instead of reading
-- every case's text. PostgreSQL rechecks every candidate against the pattern, so the results are exactly LIKE's.
-- pg_trgm ships with PostgreSQL and is a trusted extension (13+): the schema owner creates it if it may create in
-- this database; otherwise a database administrator creates it once beforehand and the first statement does nothing
-- (deploy/README.md, "Search indexes"). Oracle's V21 is a different index for the same search.
-- No entity change; grants are unchanged (an index needs none).
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE INDEX IX_CASE_SEARCH_TEXT ON CASE_SEARCH USING GIN (SEARCH_TEXT gin_trgm_ops);
