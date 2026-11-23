-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
--
-- Synthetic 500 000-comment layer for the release performance runs (WP-30), PostgreSQL only. BUILD_PLAN's performance
-- row seeds "50k cases / 500k comments", so this file is applied on top of perf/seed-cases-postgresql.sql, never alone,
-- and together they are "the 500k seed". Deterministic: every value is a function of the case and comment numbers, so
-- two runs produce the same data; every name and word is synthetic. Never applied by Flyway and never packaged. Run it
-- once, after the case seed, as the schema owner (for the local stack:
-- psql -h localhost -U trailkeep -d trailkeep -f perf/seed-comments-postgresql.sql); PostgresComments500kPerfIT applies
-- the same file to its own container.
--
-- Shape: case i has 8 comments, except every hundredth case (i % 100 = 0, 500 cases), which has 208, so the case page
-- and the history tab are also measured on long threads: 49 500 x 8 + 500 x 208 = 500 000 comments. A comment is two to
-- four sentences from a fixed set of twenty plus a reference token (173 characters on average); one comment in 997 also
-- carries the word "kestrel", which appears nowhere else, so a search for it must read every case's whole text. Each
-- comment has what posting it writes in the application: its CASE_COMMENT row, its text appended lower-cased to the
-- case's CASE_SEARCH row after a line break, and a COMMENT_ADDED audit event whose body is redacted to an HMAC-shaped
-- digest and a length. Comments are written by members of the case's team, three hours apart, from the case's opening.
-- Comment and audit ids are 900000000000 + 1000 * i + k (k the comment's place in its case), above any id the
-- application allocates.

CREATE TEMPORARY TABLE PERF_COMMENT AS
SELECT 900000000000 + 1000 * c.i + k AS ID,
       900000000 + c.i AS CASE_ID,
       900000000 + 1 + c.i % 20 + 20 * ((c.i / 20 + k) % 10) AS AUTHOR_ID,
       c.OPENED_AT + k * INTERVAL '3 hours' AS CREATED_AT,
       v.S[1 + (c.i * 31 + k * 17) % 20]
           || ' ' || v.S[1 + (c.i * 7 + k * 29 + 3) % 20]
           || CASE WHEN (c.i + k) % 2 = 0 THEN ' ' || v.S[1 + (c.i * 13 + k * 11 + 7) % 20] ELSE '' END
           || CASE WHEN (c.i + k) % 5 = 0 THEN ' Next step: confirm with the owner and update the tracker.' ELSE '' END
           || CASE WHEN (1000 * c.i + k) % 997 = 0 THEN ' Kestrel escalation noted.' ELSE '' END
           || ' Ref ' || substr(md5((1000 * c.i + k)::text), 1, 12) || '.' AS BODY
  FROM (SELECT ID - 900000000 AS I, OPENED_AT FROM CASE_RECORD WHERE ID BETWEEN 900000001 AND 900050000) AS c
 CROSS JOIN LATERAL generate_series(1, CASE WHEN c.i % 100 = 0 THEN 208 ELSE 8 END) AS k
 CROSS JOIN (SELECT ARRAY['Requested the access logs from the system owner.',
                          'Logs received and reviewed for the period in scope.',
                          'No exceptions found in the sample of forty entries.',
                          'Two entries need a second look before this can move on.',
                          'Spoke with the team lead; the control owner will confirm by Friday.',
                          'Evidence attached to the shared folder and referenced in the workpaper.',
                          'The vendor answered the questionnaire, but section four is incomplete.',
                          'Followed up on the open item from the last review.',
                          'Remediation plan agreed with the process owner.',
                          'Waiting on the policy team for the updated standard.',
                          'Retested the control after the fix and it now operates as designed.',
                          'The finding is rated moderate given the compensating controls.',
                          'Escalated the overdue response to the department manager.',
                          'Walkthrough completed and the narrative updated.',
                          'Sample selection documented, twenty-five items chosen at random.',
                          'The exception was approved by the risk committee for six months.',
                          'Training records show three people missing the annual module.',
                          'Closed the duplicate item and linked it here.',
                          'Draft memo shared for review; comments due next week.',
                          'Confirmed the change ticket and the approval trail match.'] AS S) AS v;

INSERT INTO CASE_COMMENT (ID, CASE_ID, BODY, AUTHOR_ID, AUTHOR_NAME_SNAPSHOT, CREATED_AT)
SELECT ID, CASE_ID, BODY, AUTHOR_ID, 'Perf User ' || lpad((AUTHOR_ID - 900000000)::text, 3, '0'), CREATED_AT
  FROM PERF_COMMENT;

INSERT INTO AUDIT_EVENT (ID, AT_TS, ACTOR_ID, ACTOR_NAME_SNAPSHOT, ORIGIN, CASE_ID, ENTITY_TYPE, ENTITY_ID, ACTION,
                         OLD_VALUES_JSON, NEW_VALUES_JSON)
SELECT ID, CREATED_AT, AUTHOR_ID, 'Perf User ' || lpad((AUTHOR_ID - 900000000)::text, 3, '0'), 'UI', CASE_ID, 'COMMENT',
       ID, 'COMMENT_ADDED', NULL,
       '{"body":{"hmac":"' || md5(BODY) || md5(ID::text) || '","length":' || length(BODY) || '}}'
  FROM PERF_COMMENT;

UPDATE CASE_SEARCH s
   SET SEARCH_TEXT = s.SEARCH_TEXT || a.TEXT,
       UPDATED_AT = a.LAST_AT
  FROM (SELECT CASE_ID, string_agg(E'\n' || lower(BODY), '' ORDER BY ID) AS TEXT, max(CREATED_AT) AS LAST_AT
          FROM PERF_COMMENT
         GROUP BY CASE_ID) AS a
 WHERE s.CASE_ID = a.CASE_ID;

DROP TABLE PERF_COMMENT;

ANALYZE CASE_COMMENT;
ANALYZE AUDIT_EVENT;
ANALYZE CASE_SEARCH;
