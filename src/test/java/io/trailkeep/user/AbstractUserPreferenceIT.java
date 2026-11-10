// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import java.util.Objects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Preferences once per vendor (WP-27): a person with no row has the shortcuts on; turning them off writes the row and
 * one {@code PREFERENCES_CHANGED} event with the old and new value; turning them on again updates the same row and
 * audits again; saving the value the person already has writes nothing; another person is untouched. Rows are
 * committed and removed after each test.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
abstract class AbstractUserPreferenceIT {

    @Autowired
    private UserPreferenceService preferences;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    private long person;
    private long other;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        person = seed.user();
        other = seed.user();
    }

    @AfterEach
    void removeRows() {
        for (long id : new long[] {person, other}) {
            jdbc.update("DELETE FROM AUDIT_EVENT WHERE ACTION = 'PREFERENCES_CHANGED' AND ENTITY_ID = ?", id);
            jdbc.update("DELETE FROM USER_PREFERENCE WHERE USER_ID = ?", id);
            jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id);
        }
    }

    @Test
    void shortcutsAreOnUntilSavedOffAndEachChangeIsAudited() {
        assertThat(preferences.shortcutsEnabled(person)).isTrue();

        save(person, false);
        assertThat(preferences.shortcutsEnabled(person)).isFalse();
        assertThat(preferences.shortcutsEnabled(other)).isTrue();
        assertThat(events(person)).isEqualTo(1);

        save(person, false);
        assertThat(events(person)).isEqualTo(1);

        save(person, true);
        assertThat(preferences.shortcutsEnabled(person)).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_PREFERENCE WHERE USER_ID = ?", Long.class, person))
                .isEqualTo(1);
        assertThat(events(person)).isEqualTo(2);
    }

    @Test
    void savingTheDefaultForAPersonWithNoRowWritesNothing() {
        save(other, true);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM USER_PREFERENCE WHERE USER_ID = ?", Long.class, other))
                .isZero();
        assertThat(events(other)).isZero();
    }

    private void save(long userId, boolean enabled) {
        transactions.executeWithoutResult(
                status -> preferences.setShortcutsEnabled(userId, "Synthetic Person " + userId, enabled));
    }

    private long events(long userId) {
        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'PREFERENCES_CHANGED' AND ENTITY_ID = ?",
                Long.class,
                userId));
    }
}
