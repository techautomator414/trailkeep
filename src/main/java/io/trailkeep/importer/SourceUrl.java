// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.SourceQueryValidator.Vendor;
import java.util.Set;

/**
 * Which JDBC URLs the importer may connect to (design §4.1, §10). A driver reads more than an address from its URL:
 * PostgreSQL's takes class names it instantiates ({@code socketFactory}, {@code sslfactory},
 * {@code sslhostnameverifier}, {@code sslpasswordcallback}, {@code authenticationPluginClassName}) and file names it
 * writes ({@code loggerFile}). So the URL must name the source's own driver and carry only parameters from an
 * allow-list of address, TLS and timeout settings; anything else is refused before any connection is attempted.
 */
final class SourceUrl {

    /** PostgreSQL parameters that only choose how to reach and verify the source, never code or files to write. */
    private static final Set<String> POSTGRESQL_PARAMETERS = Set.of(
            "ssl",
            "sslmode",
            "sslrootcert",
            "sslcert",
            "sslkey",
            "connectTimeout",
            "socketTimeout",
            "loginTimeout",
            "tcpKeepAlive",
            "targetServerType",
            "ApplicationName",
            "readOnly",
            "currentSchema");

    /** The one logging setting allowed, the one Testcontainers and careful operators add. */
    private static final String LOGGING_OFF = "loggerLevel=OFF";

    private SourceUrl() {}

    /**
     * Whether a resolved JDBC URL may be used for a source of this vendor.
     *
     * @param vendor the mapping's {@code source.vendor}
     * @param url the URL with its placeholders resolved
     * @return true only for the vendor's own scheme with allow-listed parameters
     */
    static boolean allowed(Vendor vendor, String url) {
        if (vendor != Vendor.POSTGRESQL || !url.startsWith("jdbc:postgresql://")) {
            return false;
        }
        int query = url.indexOf('?');
        if (query < 0) {
            return true;
        }
        for (String parameter : url.substring(query + 1).split("&", -1)) {
            int equals = parameter.indexOf('=');
            // Driver logging may only be switched off: its records can quote values.
            if (!LOGGING_OFF.equals(parameter)
                    && !POSTGRESQL_PARAMETERS.contains(equals < 0 ? parameter : parameter.substring(0, equals))) {
                return false;
            }
        }
        return true;
    }
}
