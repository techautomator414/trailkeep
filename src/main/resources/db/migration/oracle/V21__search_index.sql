-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V21 search index, Oracle (WP-30 part a2, #299; ADR-0017 has the reasons and the measurements). An Oracle Text
-- CONTEXT index on CASE_SEARCH.SEARCH_TEXT for OracleTextSearchGateway. Needs Oracle Text installed (it is by
-- default); every preference is a CTXSYS system preference, so the owner needs no CTXAPP role. BASIC_LEXER and
-- BASIC_WORDLIST rather than the language-dependent defaults (OracleTextQuery relies on their tokens); EMPTY_STOPLIST;
-- SYNC (ON COMMIT) TRANSACTIONAL, so a search never lags a write; OPTIMIZE (AUTO_DAILY) by CTXSYS's scheduler. Built
-- ONLINE: otherwise writes fail with ORA-29861 during the build while the previous release still serves.
-- The index's optimizer statistics are deleted and CASE_SEARCH's locked: with them, Oracle Text estimates every
-- CONTAINS by running it, at every execution (deploy/README.md: do not unlock or force-gather them).
-- Re-runnable after "flyway repair": an index a failed build left unusable is dropped and built again.
-- No entity change; grants unchanged: the runtime, purge and import users write CASE_SEARCH as before.
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
      || ' SYNC (ON COMMIT) TRANSACTIONAL OPTIMIZE (AUTO_DAILY)'') ONLINE';
  END IF;
  DBMS_STATS.DELETE_INDEX_STATS(USER, 'IX_CASE_SEARCH_TEXT', force => TRUE);
  DBMS_STATS.LOCK_TABLE_STATS(USER, 'CASE_SEARCH');
END;
/
