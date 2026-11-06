-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.6 the upper bounds of the admin ranges, completing V3__admin's "last line of defence". V3 carries only the
-- lower bounds; the ceilings the screens state (SlaPolicyCommand: 3650 target days, 365 reminder days;
-- CaseTypeCommand: 100 retention years) lived only in Jakarta Validation. They are named constraints of their own so
-- V3 stays untouched (Flyway checksum). AT_RISK_PCT already has both bounds (1 to 99). The statements fail if an
-- existing row is above a ceiling, which the screens could not have produced. ALTER TABLE ... ADD CONSTRAINT is the
-- same on PostgreSQL and Oracle.
ALTER TABLE SLA_POLICY ADD CONSTRAINT CK_SLA_TARGET_MAX CHECK (TARGET_DAYS <= 3650);
ALTER TABLE SLA_POLICY ADD CONSTRAINT CK_SLA_REMINDER_MAX CHECK (OVERDUE_REMINDER_DAYS <= 365);
ALTER TABLE CASE_TYPE ADD CONSTRAINT CK_CASE_TYPE_RETENTION_MAX CHECK (RETENTION_YEARS <= 100);
