-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V21 search index, PostgreSQL (WP-30 part a2, #299; ADR-0017). A trigram index on CASE_SEARCH.SEARCH_TEXT: the LIKE
-- gateway's '%word%' patterns, unchanged, are answered from it instead of reading every case's text. PostgreSQL
-- rechecks every candidate against the pattern, so the results are exactly LIKE's. Oracle's V21 is a different index
-- for the same search. No entity change; grants are unchanged (an index needs none).
--
-- pg_trgm ships with PostgreSQL and is a trusted extension (13+): the schema owner creates it, in its own schema, if
-- it may create in this database; otherwise a database administrator creates it once beforehand, in any schema, and
-- the first statement does nothing (deploy/README.md, "Search indexes"). The operator class is qualified with the
-- extension's schema, so the index is built wherever the extension lives, on the search path or not.
CREATE EXTENSION IF NOT EXISTS pg_trgm;

DO $$
DECLARE
  trgm_schema VARCHAR(63);
BEGIN
  SELECT n.nspname INTO STRICT trgm_schema
    FROM pg_extension e JOIN pg_namespace n ON n.oid = e.extnamespace
   WHERE e.extname = 'pg_trgm';
  EXECUTE format('CREATE INDEX IX_CASE_SEARCH_TEXT ON CASE_SEARCH USING GIN (SEARCH_TEXT %I.gin_trgm_ops)',
                 trgm_schema);
END
$$;
