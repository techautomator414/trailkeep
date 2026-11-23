// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.restoredrill;

import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.JdbcDatabaseContainer;

/** The drill's access to a database container: commands run in it, and its connection details. */
final class DrillDatabase {

    private DrillDatabase() {}

    /**
     * Runs a command in the database's container and fails with its output if it fails.
     *
     * @param database the container
     * @param command the command and its arguments
     * @return its standard output and error, joined
     * @throws Exception if it cannot run or exits non-zero
     */
    static String exec(JdbcDatabaseContainer<?> database, String... command) throws Exception {
        ExecResult result = database.execInContainer(command);
        String output = result.getStdout() + result.getStderr();
        if (result.getExitCode() != 0) {
            throw new IllegalStateException(command[0] + " exited " + result.getExitCode() + ":\n" + output);
        }
        return output;
    }

    static long fileSize(JdbcDatabaseContainer<?> database, String path) throws Exception {
        return Long.parseLong(exec(database, "stat", "-c", "%s", path).strip());
    }

    static JdbcConnectionDetails details(JdbcDatabaseContainer<?> database) {
        return new JdbcConnectionDetails() {
            @Override
            public String getUsername() {
                return database.getUsername();
            }

            @Override
            public String getPassword() {
                return database.getPassword();
            }

            @Override
            public String getJdbcUrl() {
                return database.getJdbcUrl();
            }
        };
    }
}
