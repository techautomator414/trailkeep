// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.TeamRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code dry-run} matching legacy people and choosing open cases' owners as the import user (WP-26 part r2, design
 * §7.3): staged people matched to synthetic users by external id, then email, an ambiguous email a WARNING, an open
 * case keeping only an eligible owner, the default owners of {@code defaults.owner.byTeam} checked for eligibility,
 * a team with open cases and no eligible default owner a FATAL issue carrying its id, the match rate per role in the
 * figures, and the default-owner file inside the mapping hash.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractDryRunPeopleIT extends DryRunSupport {

    private final List<Long> users = new ArrayList<>();
    private long south;

    @AfterEach
    void removeTheUsersAndTheSecondTeam() {
        for (long user : users) {
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID = ?", user);
            jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user);
        }
        users.clear();
        jdbc.update("DELETE FROM TEAM WHERE ID = ?", south);
    }

    @Test
    void dryRunMatchesPeopleAndNeedsEligibleDefaultOwnerForEveryTeamWithOpenCases(@TempDir Path directory)
            throws Exception {
        CaseSeed seed = new CaseSeed(jdbc);
        south = seed.team();
        long analyst = user(seed, TeamRole.ANALYST);
        long manager = user(seed, TeamRole.MANAGER);
        String shared = "shared" + analyst + "@example.test";
        jdbc.update("UPDATE APP_USER SET EMAIL = ? WHERE ID IN (?, ?)", shared, user(seed, null), user(seed, null));
        final Path owners = Files.writeString(
                directory.resolve("owners.csv"),
                "team,owner\nSeed Team " + team + ",seed" + manager + "@example.test\n");
        Path mapping = mapping(
                directory,
                MAPPING.replace("match: [email]", "match: [externalId, email]")
                        + "defaults: { owner: { byTeam: owners.csv } }\n");
        long run = extractedRun(mapping);
        String person = "INSERT INTO IMPORT_STG_PERSON (RUN_ID, LEGACY_KEY, ROW_HASH, EMAIL, EXTERNAL_ID)"
                + " VALUES (?, ?, ?, ?, ?)";
        jdbc.update(person, run, "p1", "ab".repeat(32), null, "seed-" + analyst);
        jdbc.update(person, run, "p2", "ab".repeat(32), shared.toUpperCase(Locale.ROOT), null);
        jdbc.update(person, run, "p3", "ab".repeat(32), "nobody@example.test", null);
        stage(run, "c1", "OPEN", "p1");
        stage(run, "c2", "OPEN", "p2");
        stage(run, "c3", "CLOSED", "p3");
        Object[] southern = row(run, "c4", "Synthetic open case", "OPEN", null);
        southern[5] = "Seed Team " + south;
        jdbc.update(INSERT, southern);
        jdbc.update("UPDATE IMPORT_STG_CASE SET OWNER_KEY = 'p1' WHERE RUN_ID = ? AND LEGACY_KEY = 'c4'", run);
        stageChildren(run);

        dryRun(run, mapping).close();

        assertThat(dryRunIssues(run))
                .containsExactlyInAnyOrder(
                        List.of("PERSON", "WARNING", "AMBIGUOUS_PERSON", hash("p2")),
                        List.of("CASE", "FATAL", "DEFAULT_OWNER_MISSING", ""));
        assertThat(count("SELECT DETAIL FROM IMPORT_ISSUE WHERE RUN_ID = ? AND CODE = 'DEFAULT_OWNER_MISSING'", run))
                .as("the team whose open case has no eligible owner, legacy or default")
                .isEqualTo((int) south);
        assertThat(figures(run))
                .contains(
                        "ROWS PERSON 3",
                        "PEOPLE_MATCHED OPEN_OWNER 1",
                        "PEOPLE_UNMATCHED OPEN_OWNER 2",
                        "PEOPLE_UNMATCHED CLOSED_OWNER 1",
                        "DEFAULT_OWNER_CASES " + team + " 1",
                        "DEFAULT_OWNER_CASES " + south + " 1",
                        "PEOPLE_MATCHED AUTHOR 1",
                        "PEOPLE_UNMATCHED AUTHOR 1",
                        "PEOPLE_MATCHED UPLOADER 1",
                        "ISSUES PERSON/WARNING/AMBIGUOUS_PERSON 1",
                        "ISSUES CASE/FATAL/DEFAULT_OWNER_MISSING 1");

        Files.writeString(owners, "team,owner\nSeed Team " + south + ",seed" + manager + "@example.test\n");
        assertThatThrownBy(() -> dryRun(run, mapping))
                .as("the default-owner file is part of the mapping hash")
                .hasMessage("The mapping file or a lookup file changed after validate-mapping; validate it again");
    }

    /** A user, a member of the seeded team in a role when one is given. */
    private long user(CaseSeed seed, @Nullable TeamRole role) {
        long user = seed.user();
        users.add(user);
        if (role != null) {
            seed.member(user, team, role);
        }
        return user;
    }

    private void stage(long run, String key, String status, String owner) {
        jdbc.update(INSERT, row(run, key, "Synthetic case", status, "CLOSED".equals(status) ? "SUBSTANTIATED" : null));
        jdbc.update("UPDATE IMPORT_STG_CASE SET OWNER_KEY = ? WHERE RUN_ID = ? AND LEGACY_KEY = ?", owner, run, key);
    }

    /** A comment by a matched and one by an unmatched person on c1, and a file the matched one uploaded. */
    private void stageChildren(long run) {
        Timestamp at = Timestamp.from(Instant.parse("2024-01-16T10:00:00Z"));
        String comment = "INSERT INTO IMPORT_STG_COMMENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY, AUTHOR_KEY,"
                + " WRITTEN_AT, BODY) VALUES (?, ?, ?, ?, ?, ?, ?)";
        jdbc.update(comment, run, "n1", "ab".repeat(32), "c1", "p1", at, "Synthetic note");
        jdbc.update(comment, run, "n2", "ab".repeat(32), "c1", "p3", at, "Synthetic note");
        jdbc.update(
                "INSERT INTO IMPORT_STG_ATTACHMENT (RUN_ID, LEGACY_KEY, ROW_HASH, CASE_KEY, FILE_NAME, SIZE_BYTES,"
                        + " SHA256, CONTENT_POINTER, UPLOADED_AT, UPLOADER_KEY) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                run,
                "f1",
                "ab".repeat(32),
                "c1",
                "fine.pdf",
                10L,
                "cd".repeat(32),
                "fine.pdf",
                at,
                "p1");
    }
}
