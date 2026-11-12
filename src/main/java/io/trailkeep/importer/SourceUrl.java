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
 * timeout settings; anything else is refused before any connection is attempted (26n, 26o). SQL Server's driver
 * instantiates classes named in its properties ({@code socketFactoryClass}, {@code trustManagerClass}, {@code
 * accessTokenCallbackClass}), loads native or Kerberos code ({@code integratedSecurity}, {@code authenticationScheme})
 * and reads key stores ({@code keyStoreLocation}); its address is {@code host[\instance][:port]}, its property names
 * are case-insensitive, and a value in braces may hold a {@code ;}, so braces are refused (26p).
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
            "connect_timeout",
            "transport_connect_timeout",
            "retry_count",
            "retry_delay",
            "oracle.net.CONNECT_TIMEOUT",
            "oracle.jdbc.ReadTimeout");

    /**
     * SQL Server properties that only choose the database and how to reach and verify the server, matched without
     * case as the driver does, but in ASCII only: a name the driver might fold differently is refused, not allowed.
     */
    private static final Pattern SQLSERVER_PARAMETERS = Pattern.compile(
            "databaseName|encrypt|hostNameInCertificate|serverCertificate|trustStore|trustStoreType|sslProtocol"
                    + "|loginTimeout|socketTimeout|connectRetryCount|connectRetryInterval|multiSubnetFailover"
                    + "|applicationIntent|applicationName",
            Pattern.CASE_INSENSITIVE);

    /** {@code trustServerCertificate} is allowed only as {@code false} (design §4.1). */
    private static final Pattern TRUST_SERVER_CERTIFICATE_OFF =
            Pattern.compile("trustServerCertificate=false", Pattern.CASE_INSENSITIVE);

    /** A SQL Server host: a host name or IPv4 address. */
    private static final Pattern SQLSERVER_HOST = Pattern.compile("[A-Za-z0-9.-]+");

    /** A SQL Server named instance. */
    private static final Pattern SQLSERVER_INSTANCE = Pattern.compile("[A-Za-z0-9_$-]+");

    private static final Pattern PORT = Pattern.compile("[0-9]{1,5}");

    private static final String SQLSERVER = "jdbc:sqlserver://";

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
        if (vendor == Vendor.SQLSERVER) {
            return sqlServer(url);
        }
        int query = url.indexOf('?');
        String address = query < 0 ? url : url.substring(0, query);
        boolean known = switch (vendor) {
            case POSTGRESQL -> address.startsWith("jdbc:postgresql://");
            case ORACLE ->
                address.startsWith(ORACLE_THIN)
                        && ORACLE_ADDRESS
                                .matcher(address.substring(ORACLE_THIN.length()))
                                .matches();
            case SQLSERVER -> false; // checked by sqlServer
        };
        return known && (query < 0 || parameters(vendor, url.substring(query + 1)));
    }

    /** {@code jdbc:sqlserver://address;name=value;…} with the address and every property allowed. */
    private static boolean sqlServer(String url) {
        if (!url.startsWith(SQLSERVER) || url.indexOf('{') >= 0 || url.indexOf('}') >= 0) {
            return false;
        }
        String[] parts = url.substring(SQLSERVER.length()).split(";", -1);
        if (!sqlServerAddress(parts[0])) {
            return false;
        }
        for (int i = 1; i < parts.length; i++) {
            // A trailing ';' leaves an empty part.
            if (!parts[i].isEmpty() && !sqlServerProperty(parts[i])) {
                return false;
            }
        }
        return true;
    }

    /** {@code host[\instance][:port]}. */
    private static boolean sqlServerAddress(String address) {
        int colon = address.indexOf(':');
        String host = colon < 0 ? address : address.substring(0, colon);
        int backslash = host.indexOf('\\');
        return (colon < 0 || PORT.matcher(address.substring(colon + 1)).matches())
                && SQLSERVER_HOST
                        .matcher(backslash < 0 ? host : host.substring(0, backslash))
                        .matches()
                && (backslash < 0
                        || SQLSERVER_INSTANCE
                                .matcher(host.substring(backslash + 1))
                                .matches());
    }

    /** One {@code name=value}; a property without a value is refused. */
    private static boolean sqlServerProperty(String property) {
        int equals = property.indexOf('=');
        return TRUST_SERVER_CERTIFICATE_OFF.matcher(property).matches()
                || (equals > 0
                        && SQLSERVER_PARAMETERS
                                .matcher(property.substring(0, equals))
                                .matches());
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
