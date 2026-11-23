-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
--
-- Synthetic legacy tracker at the release volume, for the importer's volume runs (WP-30 part b,
-- docs/perf/importer-volume.md). It is a SOURCE database, not Trailkeep's: the tables of the worked example's legacy
-- database (docs/importer/worked-example.md), plus an external id on each person, read by the importer through a
-- read-only source account. PostgreSQL only. Deterministic: every value is a function of the row numbers, so two runs
-- produce the same data; every name and word is synthetic. Never applied by Flyway and never packaged.
--
-- Two tokens are replaced before it runs: {{CASES}} (50 000, BUILD_PLAN's release dataset) and {{PEOPLE}} (500 000,
-- at least 5 000). PostgresImportVolumePerfIT replaces them (smaller with -Dtrailkeep.perf.import.scale) and applies
-- the file to a container of its own; by hand, replace them and run it as the database owner with psql.
--
-- Shape at the release volume:
--   people    500 000: the legacy users table, as a tracker that mirrors the whole directory holds it, leavers
--             included. Persons 1 to 1 000 carry the external id of a Trailkeep user; 1 001 to 2 000 carry no external
--             id and the email address of a Trailkeep user in other letter case; 2 001 to 2 050 carry an address two
--             Trailkeep users share (AMBIGUOUS_PERSON); the rest match nobody, half of them with an external id
--             Trailkeep does not know. Only persons 1 to 5 000 own, write or change anything.
--   cases     50 000 in ten units, one in five open ("Open" or "In Review"), the rest closed, one in ten
--             confidential, one in five hundred on hold, opened over ten years from 2016.
--   notes     500 000: 8 per case, except every hundredth case, which has 208 (49 500 x 8 + 500 x 208), two to four
--             sentences of the twenty the 500k seed uses plus a reference token, an hour apart from the opening.
--   files     2 000: one per 25 cases, about 2 KiB of text each, read by the mapping's content query.
--   history   100 000: two rows per case, a state change and a redacted description change.
-- Ids: case i is i; its notes are 1000 * i + k; its file is i; its history rows are 10 * i + 1 and 10 * i + 2.

CREATE SCHEMA dbo;
CREATE TABLE dbo.users (user_id VARCHAR(10) PRIMARY KEY, display_name VARCHAR(100), email VARCHAR(200),
  external_id VARCHAR(40));
CREATE TABLE dbo.cases (case_id INTEGER PRIMARY KEY, case_no VARCHAR(20), subject_line VARCHAR(300), details TEXT,
  unit VARCHAR(40), assignee VARCHAR(10), state VARCHAR(20), result VARCHAR(20), opened TIMESTAMP, closed TIMESTAMP,
  found_on DATE, confidential CHAR(1), acl_entries TEXT, hold_flag CHAR(1));
CREATE TABLE dbo.notes (note_id INTEGER PRIMARY KEY, case_id INTEGER, author VARCHAR(10), created TIMESTAMP,
  body TEXT);
CREATE TABLE dbo.files (file_id INTEGER PRIMARY KEY, case_id INTEGER, file_name VARCHAR(260), size_bytes BIGINT,
  uploaded TIMESTAMP, uploader VARCHAR(10), content BYTEA);
CREATE TABLE dbo.case_audit (audit_id INTEGER PRIMARY KEY, case_id INTEGER, changed_at TIMESTAMP,
  changed_by VARCHAR(10), field_name VARCHAR(40), old_val TEXT, new_val TEXT);

INSERT INTO dbo.users (user_id, display_name, email, external_id)
SELECT 'p' || lpad(p::text, 7, '0'),
       'Legacy Person ' || lpad(p::text, 7, '0'),
       CASE WHEN p <= 1000 THEN 'ext.' || lpad(p::text, 6, '0') || '@example.invalid'
            WHEN p <= 2000 THEN 'Mail.' || lpad((p - 1000)::text, 6, '0') || '@Example.INVALID'
            WHEN p <= 2050 THEN 'shared.' || lpad((p - 2000)::text, 6, '0') || '@example.invalid'
            ELSE 'former.' || lpad(p::text, 7, '0') || '@legacy.example.invalid' END,
       CASE WHEN p <= 1000 THEN 'legacy-ext-' || lpad(p::text, 6, '0')
            WHEN p > 2050 AND p % 2 = 0 THEN 'retired-' || lpad(p::text, 7, '0') END
  FROM generate_series(1, {{PEOPLE}}) AS p;

-- Case i: unit 1 + i % 10, owned by person 1 + 7i % 5000; open when i % 10 is 0 or 5.
INSERT INTO dbo.cases (case_id, case_no, subject_line, details, unit, assignee, state, result, opened, closed,
                       found_on, confidential, acl_entries, hold_flag)
SELECT i,
       'IMP-' || lpad(i::text, 6, '0'),
       (ARRAY['Heron', 'Plover', 'Godwit', 'Dunlin', 'Curlew', 'Lapwing', 'Redshank', 'Sanderling'])[1 + i % 8]
           || ' ' || (ARRAY['access review', 'policy exception', 'vendor finding', 'audit follow-up',
                            'incident report', 'training gap'])[1 + (i / 8) % 6]
           || ' ' || i,
       'Synthetic legacy description ' || i || ': ' || md5(i::text),
       'Unit ' || lpad((1 + i % 10)::text, 2, '0'),
       'p' || lpad((1 + (7 * i) % 5000)::text, 7, '0'),
       CASE i % 10 WHEN 0 THEN 'Open' WHEN 5 THEN 'In Review' ELSE 'Closed' END,
       CASE WHEN i % 5 = 0 THEN NULL WHEN i % 3 = 0 THEN 'Substantiated' ELSE 'Unfounded' END,
       TIMESTAMP '2016-01-04 09:00:00' + (i % 3650) * INTERVAL '1 day' + (i % 480) * INTERVAL '1 minute',
       CASE WHEN i % 5 = 0 THEN NULL
            ELSE TIMESTAMP '2016-01-04 09:00:00' + (i % 3650 + 10 + i % 80) * INTERVAL '1 day' END,
       DATE '2016-01-04' + (i % 3650) - (i % 5),
       CASE WHEN i % 10 = 3 THEN 'Y' ELSE 'N' END,
       NULL,
       CASE WHEN i % 500 = 0 THEN 'Y' ELSE 'N' END
  FROM generate_series(1, {{CASES}}) AS i;

INSERT INTO dbo.notes (note_id, case_id, author, created, body)
SELECT 1000 * c.case_id + k,
       c.case_id,
       'p' || lpad((1 + (13 * c.case_id + 31 * k) % 5000)::text, 7, '0'),
       c.opened + k * INTERVAL '1 hour',
       v.S[1 + (c.case_id * 31 + k * 17) % 20]
           || ' ' || v.S[1 + (c.case_id * 7 + k * 29 + 3) % 20]
           || CASE WHEN (c.case_id + k) % 2 = 0 THEN ' ' || v.S[1 + (c.case_id * 13 + k * 11 + 7) % 20] ELSE '' END
           || CASE WHEN (c.case_id + k) % 5 = 0 THEN ' Next step: confirm with the owner and update the tracker.'
                   ELSE '' END
           || ' Ref ' || substr(md5((1000 * c.case_id + k)::text), 1, 12) || '.'
  FROM dbo.cases c
 CROSS JOIN LATERAL generate_series(1, CASE WHEN c.case_id % 100 = 0 THEN 208 ELSE 8 END) AS k
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

INSERT INTO dbo.files (file_id, case_id, file_name, size_bytes, uploaded, uploader, content)
SELECT f.case_id, f.case_id, 'scan-' || f.case_id || '.txt', octet_length(f.content), f.uploaded, f.uploader,
       f.content
  FROM (SELECT c.case_id,
               c.opened + INTERVAL '30 minutes' AS uploaded,
               c.assignee AS uploader,
               convert_to(repeat('Synthetic scanned page of case ' || c.case_id || '. ', 64), 'UTF8') AS content
          FROM dbo.cases c
         WHERE c.case_id % 25 = 0) AS f;

INSERT INTO dbo.case_audit (audit_id, case_id, changed_at, changed_by, field_name, old_val, new_val)
SELECT 10 * c.case_id + 1, c.case_id, c.opened + INTERVAL '5 minutes', c.assignee, 'State', 'New', 'Open'
  FROM dbo.cases c
UNION ALL
SELECT 10 * c.case_id + 2, c.case_id, c.opened + INTERVAL '2 hours', c.assignee, 'Details',
       'Synthetic first draft ' || c.case_id || '.', c.details
  FROM dbo.cases c;

ANALYZE dbo.users;
ANALYZE dbo.cases;
ANALYZE dbo.notes;
ANALYZE dbo.files;
ANALYZE dbo.case_audit;
