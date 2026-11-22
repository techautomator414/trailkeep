-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V20 audit time index (#274), for the Compliance Admin audit search (AuditTrailService.search). Every search has a
-- required time range of at most AuditTrailService.MAX_RANGE and pages newest first by (AT_TS, ID); the V2 indexes all
-- lead with CASE_ID, ACTOR_ID or ENTITY_TYPE, so a search by time alone, or by time and action, read the whole table.
-- With this index the page is a backward range scan and the count reads only the range (evidence in the pull request).
-- No entity change: AuditEvent already maps both columns. Grants are unchanged.
CREATE INDEX IX_AUDIT_AT ON AUDIT_EVENT (AT_TS, ID);
