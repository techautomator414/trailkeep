-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V21 search index, Oracle (WP-30 part a2, #299; ADR-0006, amended 2026-11-23). An Oracle Text CONTEXT index on
-- CASE_SEARCH.SEARCH_TEXT for OracleTextSearchGateway, which narrows the LIKE search with it: at the 500k seed LIKE
-- reads every case's CLOB, twice a search (docs/perf/release-500k.md). PostgreSQL's V21 is a trigram index instead.
--
-- Parameters, each a system preference of CTXSYS, so the schema owner needs no CTXAPP role and no CTX_DDL grant:
--   DIRECT_DATASTORE, NULL_FILTER   the column's own text, already plain
--   BASIC_LEXER, BASIC_WORDLIST     Oracle's defaults whatever the database language, so tokens are the same everywhere:
--                                   runs of letters and digits, upper-cased; OracleTextQuery relies on that
--   EMPTY_STOPLIST                  every word is indexed: "the" is a word a search must find
--   SYNC (ON COMMIT)                the committing transaction indexes its rows before its commit returns
--   TRANSACTIONAL                   a query also reads rows not yet indexed, the session's own uncommitted ones included,
--                                   so a search never misses text its writer has saved (BUILD_PLAN section 4 "Search")
--   OPTIMIZE (AUTO_DAILY)           CTXSYS's scheduler optimises the index nightly (each commit adds index fragments)
-- Oracle Text must be installed (it is by default; the slim container images remove it). No entity change; grants are
-- unchanged: the runtime, purge and import users write CASE_SEARCH as before, and Oracle Text maintains the index.
--
-- Re-runnable: Oracle commits DDL, so a build that fails part-way leaves an unusable index behind. The block drops it,
-- and after "flyway repair" this script builds the index again.
DECLARE
  v_unusable PLS_INTEGER;
BEGIN
  SELECT COUNT(*) INTO v_unusable FROM USER_INDEXES
   WHERE INDEX_NAME = 'IX_CASE_SEARCH_TEXT'
     AND (STATUS <> 'VALID' OR NVL(DOMIDX_STATUS, 'VALID') <> 'VALID' OR NVL(DOMIDX_OPSTATUS, 'VALID') <> 'VALID');
  IF v_unusable > 0 THEN
    EXECUTE IMMEDIATE 'DROP INDEX IX_CASE_SEARCH_TEXT FORCE';
  END IF;
END;
/

CREATE INDEX IX_CASE_SEARCH_TEXT ON CASE_SEARCH (SEARCH_TEXT) INDEXTYPE IS CTXSYS.CONTEXT
  PARAMETERS ('DATASTORE CTXSYS.DIRECT_DATASTORE FILTER CTXSYS.NULL_FILTER LEXER CTXSYS.BASIC_LEXER
               WORDLIST CTXSYS.BASIC_WORDLIST STOPLIST CTXSYS.EMPTY_STOPLIST
               SYNC (ON COMMIT) TRANSACTIONAL OPTIMIZE (AUTO_DAILY)');
