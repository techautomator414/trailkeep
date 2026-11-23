-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
--
-- The 500 000-comment layer of perf/seed-comments-postgresql.sql for Oracle (WP-30): the same comments, search text and
-- audit events, value for value, applied on top of perf/seed-cases-oracle.sql. Synthetic and deterministic; never
-- applied by Flyway and never packaged. Statements end with a line holding only "/", as in SQL*Plus, and carry no
-- semicolon outside PL/SQL. See perf/seed-comments-postgresql.sql for the shape. Two work tables, PERF_SENTENCE and
-- PERF_COMMENT, are created and dropped again.

CREATE TABLE PERF_SENTENCE (N NUMBER(2) NOT NULL, TEXT VARCHAR2(100) NOT NULL)
/

INSERT INTO PERF_SENTENCE (N, TEXT)
SELECT 0, 'Requested the access logs from the system owner.' FROM DUAL
UNION ALL SELECT 1, 'Logs received and reviewed for the period in scope.' FROM DUAL
UNION ALL SELECT 2, 'No exceptions found in the sample of forty entries.' FROM DUAL
UNION ALL SELECT 3, 'Two entries need a second look before this can move on.' FROM DUAL
UNION ALL SELECT 4, 'Spoke with the team lead; the control owner will confirm by Friday.' FROM DUAL
UNION ALL SELECT 5, 'Evidence attached to the shared folder and referenced in the workpaper.' FROM DUAL
UNION ALL SELECT 6, 'The vendor answered the questionnaire, but section four is incomplete.' FROM DUAL
UNION ALL SELECT 7, 'Followed up on the open item from the last review.' FROM DUAL
UNION ALL SELECT 8, 'Remediation plan agreed with the process owner.' FROM DUAL
UNION ALL SELECT 9, 'Waiting on the policy team for the updated standard.' FROM DUAL
UNION ALL SELECT 10, 'Retested the control after the fix and it now operates as designed.' FROM DUAL
UNION ALL SELECT 11, 'The finding is rated moderate given the compensating controls.' FROM DUAL
UNION ALL SELECT 12, 'Escalated the overdue response to the department manager.' FROM DUAL
UNION ALL SELECT 13, 'Walkthrough completed and the narrative updated.' FROM DUAL
UNION ALL SELECT 14, 'Sample selection documented, twenty-five items chosen at random.' FROM DUAL
UNION ALL SELECT 15, 'The exception was approved by the risk committee for six months.' FROM DUAL
UNION ALL SELECT 16, 'Training records show three people missing the annual module.' FROM DUAL
UNION ALL SELECT 17, 'Closed the duplicate item and linked it here.' FROM DUAL
UNION ALL SELECT 18, 'Draft memo shared for review; comments due next week.' FROM DUAL
UNION ALL SELECT 19, 'Confirmed the change ticket and the approval trail match.' FROM DUAL
/

CREATE TABLE PERF_COMMENT AS
SELECT 900000000000 + 1000 * c.i + n.k AS ID,
       900000000 + c.i AS CASE_ID,
       900000000 + 1 + MOD(c.i, 20) + 20 * MOD(TRUNC(c.i / 20) + n.k, 10) AS AUTHOR_ID,
       c.OPENED_AT + NUMTODSINTERVAL(3 * n.k, 'HOUR') AS CREATED_AT,
       CAST(a.TEXT || ' ' || b.TEXT
           || CASE WHEN MOD(c.i + n.k, 2) = 0 THEN ' ' || d.TEXT END
           || CASE WHEN MOD(c.i + n.k, 5) = 0 THEN ' Next step: confirm with the owner and update the tracker.' END
           || CASE WHEN MOD(1000 * c.i + n.k, 997) = 0 THEN ' Kestrel escalation noted.' END
           || ' Ref ' || SUBSTR(LOWER(RAWTOHEX(STANDARD_HASH(TO_CHAR(1000 * c.i + n.k), 'MD5'))), 1, 12) || '.'
           AS VARCHAR2(4000)) AS BODY
  FROM (SELECT ID - 900000000 AS i, OPENED_AT FROM CASE_RECORD WHERE ID BETWEEN 900000001 AND 900050000) c
  JOIN (SELECT LEVEL AS k FROM DUAL CONNECT BY LEVEL <= 208) n
    ON n.k <= CASE WHEN MOD(c.i, 100) = 0 THEN 208 ELSE 8 END
  JOIN PERF_SENTENCE a ON a.N = MOD(c.i * 31 + n.k * 17, 20)
  JOIN PERF_SENTENCE b ON b.N = MOD(c.i * 7 + n.k * 29 + 3, 20)
  JOIN PERF_SENTENCE d ON d.N = MOD(c.i * 13 + n.k * 11 + 7, 20)
/

CREATE INDEX IX_PERF_COMMENT_CASE ON PERF_COMMENT (CASE_ID, ID)
/

INSERT INTO CASE_COMMENT (ID, CASE_ID, BODY, AUTHOR_ID, AUTHOR_NAME_SNAPSHOT, CREATED_AT)
SELECT ID, CASE_ID, TO_CLOB(BODY), AUTHOR_ID, 'Perf User ' || LPAD(TO_CHAR(AUTHOR_ID - 900000000), 3, '0'), CREATED_AT
  FROM PERF_COMMENT
/

INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_ID, ACTOR_NAME_SNAPSHOT, ORIGIN, CASE_ID, ENTITY_TYPE, ENTITY_ID, ACTION,
                         OLD_VALUES_JSON, NEW_VALUES_JSON)
SELECT ID, CREATED_AT, AUTHOR_ID, 'Perf User ' || LPAD(TO_CHAR(AUTHOR_ID - 900000000), 3, '0'), 'UI', CASE_ID,
       'COMMENT', ID, 'COMMENT_ADDED', NULL,
       TO_CLOB('{"body":{"hmac":"' || LOWER(RAWTOHEX(STANDARD_HASH(BODY, 'MD5')))
           || LOWER(RAWTOHEX(STANDARD_HASH(TO_CHAR(ID), 'MD5'))) || '","length":' || LENGTH(BODY) || '}}')
  FROM PERF_COMMENT
/

-- Each case's comments, lower-cased, each after a line break, appended to its search text in id order.
DECLARE
  appended CLOB;
  last_at  TIMESTAMP WITH TIME ZONE;
BEGIN
  FOR c IN (SELECT ID FROM CASE_RECORD WHERE ID BETWEEN 900000001 AND 900050000) LOOP
    appended := NULL;
    FOR m IN (SELECT BODY, CREATED_AT FROM PERF_COMMENT WHERE CASE_ID = c.ID ORDER BY ID) LOOP
      appended := appended || CHR(10) || LOWER(m.BODY);
      last_at := m.CREATED_AT;
    END LOOP;
    UPDATE CASE_SEARCH SET SEARCH_TEXT = SEARCH_TEXT || appended, UPDATED_AT = last_at WHERE CASE_ID = c.ID;
  END LOOP;
  COMMIT;
END;
/

DROP TABLE PERF_COMMENT PURGE
/

DROP TABLE PERF_SENTENCE PURGE
/

-- Not CASE_SEARCH: V21 locks its statistics, so that its Oracle Text index has none (ADR-0017).
BEGIN
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'CASE_COMMENT', cascade => TRUE);
  DBMS_STATS.GATHER_TABLE_STATS(USER, 'AUDIT_EVENT', cascade => TRUE);
END;
/
