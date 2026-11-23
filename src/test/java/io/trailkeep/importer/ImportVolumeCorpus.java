// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The importer volume run's corpus (WP-30 part b, docs/perf/importer-volume.md): its size, the mapping and lookup files
 * that read {@code perf/seed-legacy-source-postgresql.sql}, and the Trailkeep side it is matched against, written and
 * removed by the schema owner. At the release volume: 500 000 people, 50 000 cases, 500 000 notes, 2 000 files and
 * 100 000 history rows; {@code -Dtrailkeep.perf.import.scale} shrinks every count but the 5 000 people the cases name.
 */
final class ImportVolumeCorpus {

    static final String SOURCE = "perf1";

    /** The share of the release corpus to import; 1 unless {@code trailkeep.perf.import.scale} says otherwise. */
    static final double SCALE = Double.parseDouble(System.getProperty("trailkeep.perf.import.scale", "1"));

    static final int CASES = (int) Math.round(50_000 * SCALE);
    static final int PEOPLE = Math.max(5_000, (int) Math.round(500_000 * SCALE));
    static final int NOTES = CASES * 8 + CASES / 100 * 200;
    static final int FILES = CASES / 25;
    static final int EVENTS = 2 * CASES;
    static final int TEAMS = 10;

    /** The loaded cases' ids. */
    static final String LOADED = "SELECT ID FROM CASE_RECORD WHERE LEGACY_REF LIKE 'perf1:%'";

    /** Trailkeep users and teams: ids far above the sequences and the other seeds. */
    private static final long BASE = 800_000_000L;

    private static final String MAPPING = """
            version: 1
            source:
              id: perf1
              kind: jdbc
              vendor: postgresql
              url: ${import.source.url}
              user: ${import.source.user}
              timezone: UTC
            limits:
              maxErrorRate: 0.0
              batchSize: 500
            lookups:
              status: { "Open": OPEN, "In Review": OPEN, "Closed": CLOSED }
              outcome: { "Substantiated": SUBSTANTIATED, "Unfounded": UNSUBSTANTIATED }
              team: { file: lookups/teams.csv, key: legacy_unit, value: trailkeep_team }
              historyField: { "State": status, "Assignee": owner, "Details": description, "Subject": subject }
            entities:
              case:
                query: |
                  SELECT c.case_id, c.case_no, c.subject_line, c.details, c.unit, c.assignee, c.state, c.result,
                         c.opened, c.closed, c.found_on, c.confidential, c.acl_entries, c.hold_flag
                  FROM dbo.cases c
                key: case_id
                keyCase: sensitive
                fields:
                  legacyNumber: case_no
                  title: { trim: subject_line, maxLength: 200, onOverflow: truncateWithIssue }
                  description: { coalesce: [details, "(no description in the legacy record)"],
                                 maxLength: 20000, onOverflow: continueInComment }
                  teamKey: { lookup: team, from: unit, onMissing: fail }
                  ownerKey: assignee
                  status: { lookup: status, from: state, onMissing: fail }
                  closureOutcome: { lookup: outcome, from: result, onMissing: default, default: UNSUBSTANTIATED }
                  priority: { const: NORMAL }
                  sensitivity: { when: { column: confidential, equals: "Y" }, then: RESTRICTED, else: STANDARD }
                  legacyAcl: { when: { column: acl_entries, notBlank: true }, then: true, else: false }
                  legalHold: { when: { column: hold_flag, equals: "Y" }, then: true, else: false }
                  openedAt: { timestamp: opened }
                  closedAt: { timestamp: closed }
                  identifiedOn: { date: { coalesce: [found_on, opened] } }
                  caseTypeKey: { const: perf-review }
              comment:
                query: SELECT note_id, case_id, author, created, body FROM dbo.notes
                key: note_id
                parent: { entity: case, columns: [case_id] }
                fields:
                  authorKey: author
                  writtenAt: { timestamp: created }
                  body: { text: body, format: plain }
              attachment:
                query: SELECT file_id, case_id, file_name, size_bytes, uploaded, uploader FROM dbo.files
                key: file_id
                parent: { entity: case, columns: [case_id] }
                fields:
                  fileName: file_name
                  uploadedAt: { timestamp: uploaded }
                  uploaderKey: uploader
                  content: { query: "SELECT content FROM dbo.files WHERE file_id = ?", size: size_bytes }
              event:
                query: |
                  SELECT audit_id, case_id, changed_at, changed_by, field_name, old_val, new_val
                  FROM dbo.case_audit
                key: audit_id
                parent: { entity: case, columns: [case_id] }
                fields:
                  at: { timestamp: changed_at }
                  whoKey: changed_by
                  kind: { const: FIELD_CHANGE }
                  field: { lookup: historyField, from: field_name, onMissing: default, default: other }
                  oldValue: old_val
                  newValue: new_val
            attachments:
              root: "{{ROOT}}"
            people:
              query: SELECT user_id, display_name, email, external_id FROM dbo.users
              key: user_id
              match: [externalId, email]
              unmatched: snapshot
              fields: { displayName: display_name, email: email, externalId: external_id }
            defaults:
              owner: { byTeam: lookups/default-owners.csv }
            visibility:
              onUnrepresentableAcl: restricted
            """;

    private ImportVolumeCorpus() {}

    /**
     * Every staged row: people, cases, notes, files and history.
     *
     * @return their number
     */
    static long staged() {
        return (long) PEOPLE + CASES + NOTES + FILES + EVENTS;
    }

    /**
     * Every row the load writes from staging: cases, notes, files and history.
     *
     * @return their number
     */
    static long written() {
        return (long) CASES + NOTES + FILES + EVENTS;
    }

    /**
     * The legacy source's seed with its two counts in place.
     *
     * @return the SQL
     * @throws IOException if {@code perf/seed-legacy-source-postgresql.sql} cannot be read
     */
    static String legacySeed() throws IOException {
        return Files.readString(Path.of("perf", "seed-legacy-source-postgresql.sql"))
                .replace("{{CASES}}", Integer.toString(CASES))
                .replace("{{PEOPLE}}", Integer.toString(PEOPLE));
    }

    /**
     * Writes the mapping, with the attachment root under the folder, and its two lookup files: unit to team, and each
     * team's default owner, user k of team k.
     *
     * @param folder the run's folder
     * @return the mapping file
     * @throws IOException if a file cannot be written
     */
    static Path files(Path folder) throws IOException {
        Path lookups = Files.createDirectories(folder.resolve("lookups"));
        StringBuilder teams = new StringBuilder("legacy_unit,trailkeep_team\n");
        StringBuilder owners = new StringBuilder("team,owner\n");
        for (int t = 1; t <= TEAMS; t++) {
            String team = String.format(Locale.ROOT, "Import Perf Team %02d", t);
            teams.append(String.format(Locale.ROOT, "Unit %02d,", t))
                    .append(team)
                    .append('\n');
            owners.append(team)
                    .append(String.format(Locale.ROOT, ",legacy-ext-%06d", t))
                    .append('\n');
        }
        Files.writeString(lookups.resolve("teams.csv"), teams);
        Files.writeString(lookups.resolve("default-owners.csv"), owners);
        Path root = Files.createDirectories(folder.resolve("files"));
        return Files.writeString(folder.resolve("mapping.yaml"), MAPPING.replace("{{ROOT}}", root.toString()));
    }

    /**
     * The Trailkeep side: ten teams, the case type {@code perf-review} with both outcomes, and 2 101 users who have
     * signed in. User k of the first thousand has the external id person k carries, and every hundredth of them is
     * disabled; user k of the second thousand has, in lower case, the address person 1000 + k carries; fifty pairs
     * share the address of persons 2 001 to 2 050. Each of the first two thousand is an Analyst in team 1 + (k - 1) %
     * 10, but users 1 to 10 are their teams' Managers and default owners. The last user requests the run.
     *
     * @param jdbc the schema owner
     * @return the requester's id
     */
    static long trailkeepSide(JdbcTemplate jdbc) {
        List<Object[]> teams = new ArrayList<>();
        for (int t = 1; t <= TEAMS; t++) {
            teams.add(new Object[] {BASE + t, String.format(Locale.ROOT, "Import Perf Team %02d", t)});
        }
        jdbc.batchUpdate("INSERT INTO TEAM (ID, NAME) VALUES (?, ?)", teams);
        jdbc.update("INSERT INTO SLA_POLICY (ID, NAME, TARGET_DAYS) VALUES (?, ?, ?)", BASE + 1, "Import perf", 30);
        jdbc.update(
                "INSERT INTO CASE_TYPE (ID, NAME, SLA_POLICY_ID) VALUES (?, ?, ?)", BASE + 1, "perf-review", BASE + 1);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'SUBSTANTIATED', 1)",
                BASE + 1);
        jdbc.update(
                "INSERT INTO CASE_TYPE_OUTCOME (CASE_TYPE_ID, OUTCOME, SORT_ORDER) VALUES (?, 'UNSUBSTANTIATED', 2)",
                BASE + 1);
        List<Object[]> users = new ArrayList<>();
        List<Object[]> members = new ArrayList<>();
        for (int k = 1; k <= 1000; k++) {
            String six = String.format(Locale.ROOT, "%06d", k);
            long team = BASE + 1 + (k - 1) % TEAMS;
            users.add(user(BASE + 1000 + k, "legacy-ext-" + six, "ext." + six + "@example.invalid", k % 100 == 0));
            members.add(new Object[] {BASE + 1000 + k, team, k <= TEAMS ? "MANAGER" : "ANALYST"});
            users.add(user(BASE + 2000 + k, "idp-mail-" + six, "mail." + six + "@example.invalid", false));
            members.add(new Object[] {BASE + 2000 + k, team, "ANALYST"});
        }
        for (int k = 1; k <= 50; k++) {
            String six = String.format(Locale.ROOT, "%06d", k);
            users.add(user(BASE + 3000 + k, "idp-shared-a-" + six, "shared." + six + "@example.invalid", false));
            users.add(user(BASE + 4000 + k, "idp-shared-b-" + six, "shared." + six + "@example.invalid", false));
        }
        users.add(user(BASE + 5000, "import-perf-requester", "requester@example.invalid", false));
        jdbc.batchUpdate(
                "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                        + " VALUES (?, ?, ?, ?, ?, ?)",
                users);
        jdbc.batchUpdate("INSERT INTO APP_USER_TEAM (USER_ID, TEAM_ID, ROLE_IN_TEAM) VALUES (?, ?, ?)", members);
        return BASE + 5000;
    }

    /**
     * Removes what the load wrote: the loaded cases and every row that names them, the ledger and the runs' audit rows.
     * Staging and the runs themselves go with {@link ExtractJobSupport#removeTheSeededRuns}.
     *
     * @param jdbc the schema owner
     */
    static void removeImported(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM CASE_ATTACHMENT_CHUNK WHERE ATTACHMENT_ID IN (SELECT ID FROM CASE_ATTACHMENT"
                + " WHERE CASE_ID IN (" + LOADED + "))");
        for (String table : List.of(
                "IMPORT_OWNER_PENDING",
                "ALERT_LEDGER",
                "CASE_ATTACHMENT",
                "CASE_COMMENT",
                "CASE_IMPORTED_EVENT",
                "LEGAL_HOLD_EVENT",
                "CLOSURE_REQUEST",
                "AUDIT_EVENT",
                "CASE_SLA",
                "CASE_SEARCH")) {
            // Test-only cleanup over the fixed table names of this list.
            jdbc.update("DELETE FROM " + table + " WHERE CASE_ID IN (" + LOADED + ")");
        }
        jdbc.update("DELETE FROM IMPORT_LEDGER_PART WHERE LEDGER_ID IN"
                + " (SELECT ID FROM IMPORT_LEDGER WHERE SOURCE_ID = 'perf1')");
        jdbc.update("DELETE FROM IMPORT_LEDGER WHERE SOURCE_ID = 'perf1'");
        jdbc.update("DELETE FROM CASE_RECORD WHERE LEGACY_REF LIKE 'perf1:%'");
        jdbc.update("DELETE FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'IMPORT_RUN' AND ENTITY_ID IN"
                + " (SELECT ID FROM IMPORT_RUN WHERE SOURCE_ID = 'perf1')");
    }

    /**
     * Removes what {@link #trailkeepSide} seeded, once the runs that name its users are gone.
     *
     * @param jdbc the schema owner
     */
    static void removeTrailkeepSide(JdbcTemplate jdbc) {
        jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID > ? AND USER_ID < ?", BASE, BASE + 100_000);
        jdbc.update("DELETE FROM APP_USER WHERE ID > ? AND ID < ?", BASE, BASE + 100_000);
        jdbc.update("DELETE FROM TEAM WHERE ID > ? AND ID < ?", BASE, BASE + 100);
        jdbc.update("DELETE FROM CASE_TYPE_OUTCOME WHERE CASE_TYPE_ID = ?", BASE + 1);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", BASE + 1);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", BASE + 1);
    }

    private static Object[] user(long id, String externalId, String email, boolean disabled) {
        return new Object[] {
            id,
            externalId,
            "Import Perf User " + (id - BASE),
            email,
            disabled,
            Timestamp.from(Instant.parse("2026-11-01T00:00:00Z"))
        };
    }
}
