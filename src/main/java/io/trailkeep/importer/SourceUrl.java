// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.importer;

import io.trailkeep.importer.SourceQueryValidator.Vendor;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which JDBC URLs the importer may connect to (design §4.1, §10). A driver reads more than an address from its URL:
 * PostgreSQL's takes class names it instantiates ({@code socketFactory}, {@code sslfactory},
 * {@code sslhostnameverifier}, {@code sslpasswordcallback}, {@code authenticationPluginClassName}) and file names it
 * writes ({@code loggerFile}); Oracle's thin driver reads a TNS descriptor that can name wallet and trace
 * directories, and properties that load providers ({@code oracle.jdbc.provider.*}), read a whole configuration file
 * ({@code oracle.jdbc.config.file}) or write trace files ({@code oracle.jdbc.Trace}). So the URL must name the
 * source's own driver, an Oracle address must be an Easy Connect {@code host[:port]/service} (no descriptor, no TNS
 * alias, which would need a {@code tnsnames.ora}), and parameters must come from an allow-list of address, TLS and
 * timeout settings; anything else is refused before any connection is attempted (26n, 26o).
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

    /** Oracle parameters (Easy Connect Plus) that only choose how to reach and verify the source. */
    private static final Set<String> ORACLE_PARAMETERS = Set.of(
            "ssl_server_dn_match",
            "ssl_server_cert_dn",
            "oracle.net.ssl_server_dn_match",
            "oracle.net.ssl_server_cert_dn",
            "javax.net.ssl.trustStore",
            "javax.net.ssl.trustStoreType",
            "javax.net.ssl.trustStorePassword",
            "connect_timeout",
            "transport_connect_timeout",
            "retry_count",
            "retry_delay",
            "oracle.net.CONNECT_TIMEOUT",
            "oracle.jdbc.ReadTimeout");

    /** An Easy Connect address: an optional protocol, a host name or IPv4 address, an optional port, a service. */
    private static final Pattern ORACLE_ADDRESS =
            Pattern.compile("(?:tcps?://|//)?[A-Za-z0-9.-]+(?::[0-9]{1,5})?/[A-Za-z0-9._$-]+");

    /** The one logging setting allowed, the one Testcontainers and careful operators add. */
    private static final String LOGGING_OFF = "loggerLevel=OFF";

    private static final String ORACLE_THIN = "jdbc:oracle:thin:@";

    private SourceUrl() {}

    /**
     * Whether a resolved JDBC URL may be used for a source of this vendor.
     *
     * @param vendor the mapping's {@code source.vendor}
     * @param url the URL with its placeholders resolved
     * @return true only for the vendor's own scheme with allow-listed parameters
     */
    static boolean allowed(Vendor vendor, String url) {
        int query = url.indexOf('?');
        String address = query < 0 ? url : url.substring(0, query);
        boolean known = switch (vendor) {
            case POSTGRESQL -> address.startsWith("jdbc:postgresql://");
            case ORACLE ->
                address.startsWith(ORACLE_THIN)
                        && ORACLE_ADDRESS
                                .matcher(address.substring(ORACLE_THIN.length()))
                                .matches();
            case SQLSERVER -> false;
        };
        return known && (query < 0 || parameters(vendor, url.substring(query + 1)));
    }

    private static boolean parameters(Vendor vendor, String query) {
        for (String parameter : query.split("&", -1)) {
            int equals = parameter.indexOf('=');
            String name = equals < 0 ? parameter : parameter.substring(0, equals);
            // PostgreSQL's driver logging may only be switched off: its records can quote values.
            boolean listed = vendor == Vendor.ORACLE
                    ? ORACLE_PARAMETERS.contains(name)
                    : LOGGING_OFF.equals(parameter) || POSTGRESQL_PARAMETERS.contains(name);
            if (!listed) {
                return false;
            }
        }
        return true;
    }
}
