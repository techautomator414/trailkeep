-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V6 SLA evaluation (WP-21 part a), shipped with the AlertLedger and JobRun entities (BUILD_PLAN §6, ADR-0007).
--
-- ALERT_LEDGER: one row per alert a job decided to raise, never changed. Its unique key is the idempotency rule of
-- BUILD_PLAN §6: a case gets each alert type once per SLA cycle and period, across restarts and replicas. PERIOD_KEY
-- is 'CYCLE' for the once-per-cycle alerts (at risk, overdue); the reminders of WP-17b key their repeats by day.
-- The runtime user may SELECT and INSERT only (R__grants.sql); the ledger outlives purge like the audit trail.
CREATE TABLE ALERT_LEDGER (
  ID          ${id}        NOT NULL,
  CASE_ID     ${id}        NOT NULL,
  ALERT_TYPE  VARCHAR(32)  NOT NULL,
  SLA_CYCLE   ${int}       NOT NULL,
  PERIOD_KEY  VARCHAR(32)  NOT NULL,
  RECORDED_AT ${tstz}      NOT NULL,
  CONSTRAINT PK_ALERT_LEDGER PRIMARY KEY (ID),
  CONSTRAINT FK_ALERT_LEDGER_CASE FOREIGN KEY (CASE_ID) REFERENCES CASE_RECORD (ID),
  CONSTRAINT UK_ALERT_LEDGER UNIQUE (CASE_ID, ALERT_TYPE, SLA_CYCLE, PERIOD_KEY),
  CONSTRAINT CK_ALERT_LEDGER_TYPE CHECK (ALERT_TYPE IN
    ('SLA_AT_RISK','SLA_OVERDUE','SLA_OVERDUE_REPEAT','UPDATE_REMINDER','CLOSURE_REMINDER')),
  CONSTRAINT CK_ALERT_LEDGER_CYCLE CHECK (SLA_CYCLE >= 1)
);

CREATE SEQUENCE SEQ_ALERT_LEDGER START WITH 1 INCREMENT BY 50;

-- JOB_RUN: the cluster truth of each scheduled job (BUILD_PLAN §6, IMPLEMENTATION_PLAN A.2 #30): when it last
-- succeeded on any replica and how many items it handled. The health indicator and the System screen (WP-25) read it,
-- so a replica that did not win the lock is never reported unhealthy for it.
CREATE TABLE JOB_RUN (
  JOB_NAME        VARCHAR(64) NOT NULL,
  LAST_SUCCESS_AT ${tstz}     NOT NULL,
  LAST_ITEMS      ${int}      NOT NULL,
  CONSTRAINT PK_JOB_RUN PRIMARY KEY (JOB_NAME),
  CONSTRAINT CK_JOB_RUN_ITEMS CHECK (LAST_ITEMS >= 0)
);

-- SHEDLOCK: ShedLock's JDBC provider table, in its documented shape. LOCK_UNTIL and LOCKED_AT hold UTC database time
-- (usingDbTime), written and compared by ShedLock's own statements, so they are timestamps without a time zone.
CREATE TABLE SHEDLOCK (
  NAME       VARCHAR(64)  NOT NULL,
  LOCK_UNTIL ${ts}        NOT NULL,
  LOCKED_AT  ${ts}        NOT NULL,
  LOCKED_BY  VARCHAR(255) NOT NULL,
  CONSTRAINT PK_SHEDLOCK PRIMARY KEY (NAME)
);
