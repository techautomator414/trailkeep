-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V14 uploader name snapshot (WP-26 part g, docs/design/importer.md §7.3, §8.1 R6 7), shipped with its mapping on
-- CaseAttachment. An imported attachment whose legacy uploader matched no Trailkeep user is recorded as uploaded by
-- the "Trailkeep import" principal (UPLOADED_BY), with the legacy uploader's name kept here. Null for files uploaded in
-- Trailkeep, whose uploader is a person. ALTER TABLE ... ADD <column> <type> is the same on PostgreSQL and Oracle.
ALTER TABLE CASE_ATTACHMENT ADD UPLOADER_NAME_SNAPSHOT VARCHAR(200);
