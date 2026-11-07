-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V7 scheduled report delivery (WP-23 part a), shipped with the ReportSchedule and ReportRun entities (BUILD_PLAN §6
-- "Aging report delivery", §4.5).
--
-- REPORT_SCHEDULE: at most one row per team. The hourly report-delivery job takes the enabled rows whose NEXT_RUN_AT
-- has come, renders that team's aging report and moves NEXT_RUN_AT on by a week in the same transaction, so a run is
-- never repeated (the job also holds its ShedLock lock). RUN_WEEKDAY (ISO, 1 = Monday) and RUN_HOUR are local to
-- app.timezone; NEXT_RUN_AT is the instant they next fall on. VERSION guards the job against a concurrent edit.
-- The runtime user may SELECT, INSERT and UPDATE (R__grants.sql): a schedule is disabled, never deleted.
CREATE TABLE REPORT_SCHEDULE (
  ID          ${id}   NOT NULL,
  TEAM_ID     ${id}   NOT NULL,
  ENABLED     ${bool} DEFAULT ${true} NOT NULL,
  RUN_WEEKDAY ${int}  NOT NULL,
  RUN_HOUR    ${int}  NOT NULL,
  NEXT_RUN_AT ${tstz} NOT NULL,
  LAST_RUN_AT ${tstz},
  VERSION     ${int}  DEFAULT 0 NOT NULL,
  CONSTRAINT PK_REPORT_SCHEDULE PRIMARY KEY (ID),
  CONSTRAINT UK_REPORT_SCHEDULE_TEAM UNIQUE (TEAM_ID),
  CONSTRAINT FK_REPORT_SCHEDULE_TEAM FOREIGN KEY (TEAM_ID) REFERENCES TEAM (ID),
  CONSTRAINT CK_REPORT_SCHEDULE_DAY CHECK (RUN_WEEKDAY BETWEEN 1 AND 7),
  CONSTRAINT CK_REPORT_SCHEDULE_HOUR CHECK (RUN_HOUR BETWEEN 0 AND 23)
);
CREATE INDEX IX_REPORT_SCHEDULE_DUE ON REPORT_SCHEDULE (ENABLED, NEXT_RUN_AT);

CREATE SEQUENCE SEQ_REPORT_SCHEDULE START WITH 1 INCREMENT BY 50;

-- REPORT_RUN: one rendered report, kept as it was sent: the same HTML and CSV the report pages give a Manager of the
-- team, as of AS_OF. HTML and CSV hold case numbers, titles and people's names, never a description, subject or
-- message (§3.3); no SQL function is applied to them. The mail only links to the run. The runtime user may SELECT and
-- INSERT only: a run is history.
CREATE TABLE REPORT_RUN (
  ID          ${id}       NOT NULL,
  SCHEDULE_ID ${id}       NOT NULL,
  TEAM_ID     ${id}       NOT NULL,
  REPORT      VARCHAR(16) NOT NULL,
  RAN_AT      ${tstz}     NOT NULL,
  AS_OF       DATE        NOT NULL,
  LINE_COUNT  ${int}      NOT NULL,
  HTML        ${clob}     NOT NULL,
  CSV         ${clob}     NOT NULL,
  CONSTRAINT PK_REPORT_RUN PRIMARY KEY (ID),
  CONSTRAINT FK_REPORT_RUN_SCHEDULE FOREIGN KEY (SCHEDULE_ID) REFERENCES REPORT_SCHEDULE (ID),
  CONSTRAINT FK_REPORT_RUN_TEAM FOREIGN KEY (TEAM_ID) REFERENCES TEAM (ID),
  CONSTRAINT CK_REPORT_RUN_REPORT CHECK (REPORT IN ('AGING')),
  CONSTRAINT CK_REPORT_RUN_LINES CHECK (LINE_COUNT >= 0)
);
CREATE INDEX IX_REPORT_RUN_TEAM ON REPORT_RUN (TEAM_ID, RAN_AT);
CREATE INDEX IX_REPORT_RUN_SCHEDULE ON REPORT_RUN (SCHEDULE_ID);

CREATE SEQUENCE SEQ_REPORT_RUN START WITH 1 INCREMENT BY 50;

-- The outbox's REPORT_RUN_ID (V4.8) names the run a scheduled mail delivers; its foreign key arrives with the table.
ALTER TABLE NOTIFICATION_OUTBOX
  ADD CONSTRAINT FK_OUTBOX_REPORT_RUN FOREIGN KEY (REPORT_RUN_ID) REFERENCES REPORT_RUN (ID);
CREATE INDEX IX_OUTBOX_REPORT_RUN ON NOTIFICATION_OUTBOX (REPORT_RUN_ID);
