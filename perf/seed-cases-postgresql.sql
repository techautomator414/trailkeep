-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
--
-- Synthetic 50 000-case seed for the cases-list performance runs (WP-15 part 5), PostgreSQL only. Deterministic: every
-- value is a function of the row number, so two runs produce the same data; every name is synthetic. It is never
-- applied by Flyway and never packaged. Run it once, on an empty schema migrated by the application, as the schema
-- owner (for the local stack: psql -h localhost -U trailkeep -d trailkeep -f perf/seed-cases-postgresql.sql);
-- PerfCasesListIT applies the same file to its own container.
--
-- Shape: 200 people in 20 teams (10 each: 9 Analysts and 1 Manager), 5 case types under one 30-day policy, 50 000
-- cases spread evenly over the teams (2 500 each), about 70 % open, 10 % pending closure, 15 % closed and 5 % archived,
-- 10 % restricted, 1 in 7 high priority, identified over 300 days of 2026. Each case has its SLA row and its
-- lower-cased search text (title and description, as the application writes it since V4_5). Ids start at 900000001
-- and case numbers use the prefix PERF, so the seed never collides with rows the application creates.

INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)
SELECT 900000000 + u,
       'perf-user-' || lpad(u::text, 3, '0'),
       'Perf User ' || lpad(u::text, 3, '0'),
       'perf-user-' || lpad(u::text, 3, '0') || '@example.invalid',
       FALSE,
       TIMESTAMP WITH TIME ZONE '2026-01-01 00:00:00+00'
  FROM generate_series(1, 200) AS u;

INSERT INTO TEAM (ID, NAME)
SELECT 900000000 + t, 'Perf Team ' || lpad(t::text, 2, '0')
  FROM generate_series(1, 20) AS t;

-- Person u belongs to team 1 + (u - 1) % 20; the last of each team's ten (u > 180) is its Manager.
INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM)
SELECT 900000000 + u, 900000000 + 1 + (u - 1) % 20, CASE WHEN u > 180 THEN 'MANAGER' ELSE 'ANALYST' END
  FROM generate_series(1, 200) AS u;

INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (900000001, 'Perf 30-day policy', 30);

INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID)
SELECT 900000000 + k, 'Perf case type ' || k, 900000001
  FROM generate_series(1, 5) AS k;

-- Case i: team 1 + i % 20, owned by one of that team's ten people, type 1 + i % 5 (offset from the team so the two
-- vary independently), identified on day i % 300 of 2026 and opened the next day.
INSERT INTO CASE_RECORD (ID, CASE_NUMBER, TITLE, DESCRIPTION, SUBJECT_TEXT, CASE_TYPE_ID, TEAM_ID, OWNER_ID, PRIORITY,
                         STATUS, SENSITIVITY, IDENTIFIED_ON, OPENED_AT, CREATED_AT, CREATED_BY, UPDATED_AT, UPDATED_BY)
SELECT 900000000 + i,
       'PERF-2026-' || lpad(i::text, 6, '0'),
       (ARRAY['Heron', 'Plover', 'Godwit', 'Dunlin', 'Curlew', 'Lapwing', 'Redshank', 'Sanderling'])[1 + i % 8]
           || ' ' || (ARRAY['access review', 'policy exception', 'vendor finding', 'audit follow-up',
                            'incident report', 'training gap'])[1 + (i / 8) % 6]
           || ' ' || i,
       'Synthetic description ' || i || ': ' || md5(i::text) || ' '
           || (ARRAY['estuary', 'saltmarsh', 'mudflat', 'shingle', 'reedbed'])[1 + (i / 48) % 5],
       'Synthetic subject ' || i,
       900000000 + 1 + (i / 7) % 5,
       900000000 + 1 + i % 20,
       900000000 + 1 + i % 20 + 20 * ((i / 20) % 10),
       CASE WHEN i % 7 = 0 THEN 'HIGH' ELSE 'NORMAL' END,
       CASE WHEN i % 20 < 14 THEN 'OPEN' WHEN i % 20 < 16 THEN 'PENDING_CLOSURE'
            WHEN i % 20 < 19 THEN 'CLOSED' ELSE 'ARCHIVED' END,
       CASE WHEN i % 10 = 3 THEN 'RESTRICTED' ELSE 'STANDARD' END,
       DATE '2026-01-01' + (i % 300),
       TIMESTAMP WITH TIME ZONE '2026-01-02 09:00:00+00' + (i % 300) * INTERVAL '1 day' + (i % 600) * INTERVAL '1 minute',
       TIMESTAMP WITH TIME ZONE '2026-01-02 09:00:00+00' + (i % 300) * INTERVAL '1 day',
       900000000 + 1 + i % 20 + 20 * ((i / 20) % 10),
       TIMESTAMP WITH TIME ZONE '2026-01-02 09:00:00+00' + (i % 300) * INTERVAL '1 day',
       900000000 + 1 + i % 20 + 20 * ((i / 20) % 10)
  FROM generate_series(1, 50000) AS i;

-- SLA rows: open cases spread over on track, at risk and overdue; closed and archived ones met or breached.
INSERT INTO CASE_SLA (CASE_ID, SLA_STATE, SLA_DUE_AT, AT_RISK_AT)
SELECT c.ID,
       CASE WHEN c.STATUS IN ('CLOSED', 'ARCHIVED') THEN (CASE WHEN c.ID % 4 = 0 THEN 'BREACHED' ELSE 'MET' END)
            WHEN c.ID % 9 = 0 THEN 'OVERDUE' WHEN c.ID % 5 = 0 THEN 'AT_RISK' ELSE 'ON_TRACK' END,
       (c.IDENTIFIED_ON + 31)::timestamp AT TIME ZONE 'UTC',
       (c.IDENTIFIED_ON + 25)::timestamp AT TIME ZONE 'UTC'
  FROM CASE_RECORD c
 WHERE c.ID > 900000000;

INSERT INTO CASE_SEARCH (CASE_ID, SEARCH_TEXT, UPDATED_AT)
SELECT c.ID, lower(c.TITLE || ' ' || c.DESCRIPTION), c.UPDATED_AT
  FROM CASE_RECORD c
 WHERE c.ID > 900000000;

ANALYZE APP_USER;
ANALYZE APP_USER_TEAM;
ANALYZE TEAM;
ANALYZE CASE_TYPE;
ANALYZE CASE_RECORD;
ANALYZE CASE_SLA;
ANALYZE CASE_SEARCH;
