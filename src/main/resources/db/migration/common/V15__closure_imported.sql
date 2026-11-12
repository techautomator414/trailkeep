-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V15 imported closure marker (WP-26 part h, docs/design/importer.md §7.1, §8.1 R6 8), shipped with its mapping on
-- ClosureRequest. An imported closed case carries one APPROVED closure request, requested by the "Trailkeep import"
-- principal and decided by the "Legacy approval (imported)" principal at the legacy closing time. IMPORTED marks it, so
-- the case page says "Imported closure" and nobody mistakes it for an approval given in Trailkeep. False for every
-- request made in Trailkeep. ALTER TABLE ... ADD <column> <type> DEFAULT ... NOT NULL is the same on both vendors.
ALTER TABLE CLOSURE_REQUEST ADD IMPORTED ${bool} DEFAULT ${false} NOT NULL;
