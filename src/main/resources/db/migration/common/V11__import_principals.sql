-- SPDX-License-Identifier: Apache-2.0
-- Copyright 2026 Charlie Genova and contributors
-- V11 import principals (WP-26 part c, ADR-0015, docs/design/importer.md §7.3): the two APP_USER rows that act for the
-- importer, so imported records name no real person as their creator or as the decider of a historical closure.
--   -1001 "Trailkeep import": creator and updater of imported cases, owner of unmatched closed cases, uploader of
--         imported files, requester of imported closures, placer of import holds, actor of import audit events.
--   -1002 "Legacy approval (imported)": approver and decider of imported closures, and nothing else.
-- Fixed negative ids (ImportPeopleService.IMPORTER_ID, LEGACY_APPROVAL_ID), which SEQ_APP_USER (from 1) never issues,
-- and outside every sentinel: the web layer reads a malformed id as -1, which must never name a row. DISABLED, so
-- sign-in refuses them; no team and no global role, so CaseScope grants them nothing. The external ids are reserved
-- (urn:trailkeep:principal:...), not
-- subjects any identity provider issues, and the user module never lists, enables or gives a role to a row whose
-- external id has that prefix. The .invalid addresses (RFC 2606) can never receive mail.
INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)
  VALUES (-1001, 'urn:trailkeep:principal:import', 'Trailkeep import', 'import@trailkeep.invalid', ${true},
          CURRENT_TIMESTAMP);
INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)
  VALUES (-1002, 'urn:trailkeep:principal:legacy-approval', 'Legacy approval (imported)',
          'legacy-approval@trailkeep.invalid', ${true}, CURRENT_TIMESTAMP);
