-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V4.4 the NOT_SCANNED scan status (ADR-0013), shipped with ScanStatus.NOT_SCANNED. Version 4.4 sorts after
-- V4_3__attachment and before V5__closure (IMPLEMENTATION_PLAN A.4 #37).
--
-- A deployment that sets app.attachments.scanning=external scans uploads upstream or at the endpoints; Trailkeep
-- then stores each file as NOT_SCANNED, never as CLEAN. Only the status CHECK widens; CK_ATTACHMENT_RELEASE is
-- unchanged, so RELEASED still requires its who, when and why, and every other status (NOT_SCANNED included) requires
-- them absent. DROP CONSTRAINT and ADD CONSTRAINT by name are valid on PostgreSQL and Oracle alike.
ALTER TABLE CASE_ATTACHMENT DROP CONSTRAINT CK_ATTACHMENT_SCAN;
ALTER TABLE CASE_ATTACHMENT ADD CONSTRAINT CK_ATTACHMENT_SCAN
  CHECK (SCAN_STATUS IN ('CLEAN','QUARANTINED','RELEASED','NOT_SCANNED'));
