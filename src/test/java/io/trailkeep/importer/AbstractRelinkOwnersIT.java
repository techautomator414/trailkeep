// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamRole;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code relink-owners} as the import user (WP-26 part z2, design §7.3): after a load gave seven open cases the default
 * owner, people sign in, and a {@code RELINK} run of the source first forecasts, then with {@code import.apply} does,
 * what happens to each pending case: handed to its legacy owner (once, audited, the pending row gone), or left alone
 * because the address is shared, the person may not own the team's cases or nobody signed in (the pending row stays),
 * or because someone reassigned the case meanwhile or it was closed (the pending row goes, since the case can never
 * be relinked). A run that is not a {@code RELINK} run, or was applied already, is refused.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
abstract class AbstractRelinkOwnersIT extends LoadSupport {

    /** The user c1's legacy owner signs in as, after the load. */
    private long legacy;

    @Test
    void relinkOwnersForecastsThenHandsEachPendingCaseToItsSignedInLegacyOwnerOnly(@TempDir Path directory)
            throws Exception {
        Path mapping = mapping(directory);
        long run = loadedWithPendingOwners(mapping);
        long relink = relinkRun();

        command("relink-owners", relink, mapping).close();

        Map<String, Long> outcomes = Map.of(
                "AMBIGUOUS", 1L,
                "INELIGIBLE", 1L,
                "CLOSED", 1L,
                "CLOSURE_PENDING", 1L,
                "OWNER_CHANGED", 1L,
                "RELINKED", 1L,
                "UNMATCHED", 1L);
        assertThat(relinkFigures(relink))
                .as("the dry run forecasts each outcome")
                .isEqualTo(outcomes);
        assertThat(jdbc.queryForObject("SELECT STATUS FROM IMPORT_RUN WHERE ID = ?", String.class, relink))
                .isEqualTo("DRY_RUN");
        assertThat(number(loadedCase("c1"), "OWNER_ID")).as("nothing changed").isEqualTo(manager);
        assertThat(count("SELECT COUNT(*) FROM IMPORT_OWNER_PENDING WHERE RUN_ID = ?", run))
                .isEqualTo(7);

        command("relink-owners", relink, mapping, "--import.apply=true").close();

        assertThat(relinkFigures(relink))
                .as("applying did what the dry run forecast")
                .isEqualTo(outcomes);
        assertThat(jdbc.queryForMap("SELECT STATUS, ROWS_LOADED FROM IMPORT_RUN WHERE ID = ?", relink))
                .containsEntry("STATUS", "LOADED")
                .satisfies(row -> assertThat(number(row, "ROWS_LOADED")).isEqualTo(1));
        assertThat(number(loadedCase("c1"), "OWNER_ID")).isEqualTo(legacy);
        assertThat(jdbc.queryForMap(
                        "SELECT ACTOR_ID, ORIGIN, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                                + " WHERE CASE_ID = ? AND ACTION = 'CASE_OWNER_RELINKED'",
                        caseId("c1")))
                .containsEntry("ORIGIN", "IMPORT")
                .satisfies(row -> assertThat(number(row, "ACTOR_ID")).isEqualTo(ImportPeopleService.IMPORTER_ID))
                .satisfies(row ->
                        assertThat(String.valueOf(row.get("OLD_VALUES_JSON"))).contains("\"ownerId\":" + manager))
                .satisfies(row -> assertThat(String.valueOf(row.get("NEW_VALUES_JSON")))
                        .contains("\"ownerId\":" + legacy, "\"runId\":" + relink));
        assertThat(jdbc.queryForList("SELECT CASE_ID FROM IMPORT_OWNER_PENDING WHERE RUN_ID = ?", Long.class, run))
                .as("only cases that may still be relinked wait: shared address, reader, nobody, closure pending")
                .containsExactlyInAnyOrder(caseId("c2"), caseId("c3"), caseId("c6"), caseId("c7"));
        assertThat(number(loadedCase("c4"), "OWNER_ID"))
                .as("the person who reassigned it wins")
                .isEqualTo(analyst);
        assertThat(number(loadedCase("c3"), "OWNER_ID")).isEqualTo(manager);

        assertThat(refusalOf(relink, mapping)).as("applied already").isEqualTo(LoadRefusal.NOT_LOADABLE);
        assertThat(refusalOf(run, mapping)).as("not a relink run").isEqualTo(LoadRefusal.NOT_LOADABLE);
    }

    /**
     * A load that gives seven open cases the default owner, then people sign in: c1's legacy owner as an analyst, two
     * people sharing c2's owner's address, c3's owner as a reader, and c4's, c5's and c7's owners as analysts; c4 is
     * reassigned meanwhile, c5 closed and c7 sent for closure; c6's owner never signs in.
     */
    private long loadedWithPendingOwners(Path mapping) throws Exception {
        long run = signedOffRun(mapping);
        String person = "INSERT INTO IMPORT_STG_PERSON (RUN_ID, LEGACY_KEY, ROW_HASH, DISPLAY_NAME, EMAIL)"
                + " VALUES (?, ?, ?, ?, ?)";
        for (String key : List.of("p3", "p4", "p5", "p6", "p7", "p8")) {
            jdbc.update(person, run, key, "ab".repeat(32), "Synthetic Owner " + key, key + "@legacy.example.test");
        }
        List<String> owners = List.of("p2", "p3", "p4", "p5", "p6", "p7", "p8");
        for (int i = 0; i < owners.size(); i++) {
            stage(run, "c" + (i + 1), "OPEN", owners.get(i));
        }
        load(run, mapping).close();
        legacy = signedIn("NOBODY@example.test", TeamRole.ANALYST);
        signedIn("p3@legacy.example.test", TeamRole.ANALYST);
        signedIn("p3@legacy.example.test", TeamRole.ANALYST);
        signedIn("p4@legacy.example.test", TeamRole.READER);
        for (String key : List.of("p5", "p6", "p8")) {
            signedIn(key + "@legacy.example.test", TeamRole.ANALYST);
        }
        jdbc.update("UPDATE CASE_RECORD SET OWNER_ID = ? WHERE ID = ?", analyst, caseId("c4"));
        jdbc.update("UPDATE CASE_RECORD SET STATUS = 'CLOSED' WHERE ID = ?", caseId("c5"));
        jdbc.update("UPDATE CASE_RECORD SET STATUS = 'PENDING_CLOSURE' WHERE ID = ?", caseId("c7"));
        return run;
    }

    private long signedIn(String email, TeamRole role) {
        CaseSeed seed = new CaseSeed(jdbc);
        long user = seed.user();
        users.add(user);
        jdbc.update("UPDATE APP_USER SET EMAIL = ? WHERE ID = ?", email, user);
        seed.member(user, team, role);
        return user;
    }

    private long caseId(String key) {
        return number(loadedCase(key), "ID");
    }

    private Map<String, Long> relinkFigures(long run) {
        return jdbc
                .query(
                        "SELECT DIMENSION, METRIC_VALUE FROM IMPORT_RUN_SUMMARY WHERE RUN_ID = ? AND METRIC = 'RELINK'",
                        (rs, index) -> Map.entry(rs.getString(1), rs.getLong(2)),
                        run)
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** The code a refused {@code relink-owners} names, applying. */
    private LoadRefusal refusalOf(long run, Path mapping) {
        Throwable failure = catchThrowable(() -> command("relink-owners", run, mapping, "--import.apply=true"));
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof LoadRefusal.RefusedException refused) {
                return refused.refusal();
            }
        }
        throw new AssertionError("relink-owners was not refused", failure);
    }
}
