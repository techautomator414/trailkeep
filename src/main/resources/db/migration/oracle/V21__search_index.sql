-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V21 search index, Oracle (WP-30 part a2, #299; ADR-0017). An Oracle Text CONTEXT index on
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
-- No optimizer statistics on the index. When a CONTEXT index has them, Oracle Text estimates a CONTAINS predicate's
-- selectivity by running the query, and the optimizer asks for that estimate at every execution of a statement whose
-- query is bound: at the 500k seed it doubled every search. Without them the estimate is immediate. So the index's
-- statistics are deleted and CASE_SEARCH's are locked as they stand, which the automatic statistics job respects
-- (deploy/README.md: do not unlock them or gather them with force). The table is read by its primary key, the index
-- or a full scan, which need no fresh statistics.
--
-- Re-runnable, as Oracle commits DDL: after a failure and "flyway repair", the block drops an index a failed build left
-- unusable, builds the index unless it is there and usable, and deletes and locks the statistics again.
DECLARE
  v_found  PLS_INTEGER;
  v_usable PLS_INTEGER;
BEGIN
  SELECT COUNT(*),
         COUNT(CASE WHEN STATUS = 'VALID' AND NVL(DOMIDX_STATUS, 'VALID') = 'VALID'
                         AND NVL(DOMIDX_OPSTATUS, 'VALID') = 'VALID' THEN 1 END)
    INTO v_found, v_usable
    FROM USER_INDEXES
   WHERE INDEX_NAME = 'IX_CASE_SEARCH_TEXT';
  IF v_found > v_usable THEN
    EXECUTE IMMEDIATE 'DROP INDEX IX_CASE_SEARCH_TEXT FORCE';
  END IF;
  IF v_usable = 0 THEN
    EXECUTE IMMEDIATE 'CREATE INDEX IX_CASE_SEARCH_TEXT ON CASE_SEARCH (SEARCH_TEXT) INDEXTYPE IS CTXSYS.CONTEXT'
      || ' PARAMETERS (''DATASTORE CTXSYS.DIRECT_DATASTORE FILTER CTXSYS.NULL_FILTER LEXER CTXSYS.BASIC_LEXER'
      || ' WORDLIST CTXSYS.BASIC_WORDLIST STOPLIST CTXSYS.EMPTY_STOPLIST'
      || ' SYNC (ON COMMIT) TRANSACTIONAL OPTIMIZE (AUTO_DAILY)'')';
  END IF;
  DBMS_STATS.DELETE_INDEX_STATS(USER, 'IX_CASE_SEARCH_TEXT', force => TRUE);
  DBMS_STATS.LOCK_TABLE_STATS(USER, 'CASE_SEARCH');
END;
/
