// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.user;

import java.util.Collection;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Removes what the sign-in tests commit. A sign-in runs its own transactions, so its users, roles, memberships and
 * audit events outlive the test, and any later test that counts users or Compliance Admins would see them. Test-only
 * cleanup as the schema owner (the application user has no DELETE grant on {@code AUDIT_EVENT}), in foreign-key order.
 */
final class CommittedSignIns {

    private CommittedSignIns() {}

    /** A mail address no other test matches on, one per subject. */
    static String email(String subject) {
        return subject + "@example.test";
    }

    static void remove(JdbcTemplate jdbc, Collection<String> subjects, Collection<Long> teams) {
        for (String subject : subjects) {
            for (Long id : jdbc.queryForList("SELECT ID FROM APP_USER WHERE EXTERNAL_ID = ?", Long.class, subject)) {
                jdbc.update(
                        "DELETE FROM AUDIT_EVENT WHERE ACTOR_ID = ? OR (ENTITY_TYPE = 'USER' AND ENTITY_ID = ?)",
                        id,
                        id);
                jdbc.update("DELETE FROM APP_USER_GLOBAL_ROLE WHERE USER_ID = ?", id);
                jdbc.update("DELETE FROM APP_USER_TEAM WHERE USER_ID = ?", id);
                jdbc.update("DELETE FROM APP_USER WHERE ID = ?", id);
            }
        }
        for (Long team : teams) {
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        }
    }
}
