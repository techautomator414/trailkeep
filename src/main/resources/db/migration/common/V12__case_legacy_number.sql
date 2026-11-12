-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V12 legacy number and owner snapshot (WP-26 part d, docs/design/importer.md §7.1, §7.3), shipped with their mapping
-- on CaseRecord. Both stay null for cases created in Trailkeep.
-- LEGACY_NUMBER: the number people knew the case by in the tracker it was imported from, shown on the case page as
-- "Legacy number" and part of the search text, also after an edit. LEGACY_REF (V4) stays the internal identity and is
-- not shown.
-- OWNER_NAME_SNAPSHOT: the legacy owner's name when they could not be matched to a Trailkeep user, so the case is
-- owned by the "Trailkeep import" principal (closed cases) or the team's default owner (open cases).
-- ALTER TABLE ... ADD <column> <type> is the same on PostgreSQL and Oracle.
ALTER TABLE CASE_RECORD ADD LEGACY_NUMBER VARCHAR(100);
ALTER TABLE CASE_RECORD ADD OWNER_NAME_SNAPSHOT VARCHAR(200);
