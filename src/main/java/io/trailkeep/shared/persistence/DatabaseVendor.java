// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.shared.persistence;

import java.util.Locale;

/** The two supported database vendors. The lower-case name matches the vendor migration folder. */
public enum DatabaseVendor {
    /** Oracle Database (the deployment target). */
    ORACLE("Oracle"),
    /** PostgreSQL (local development and every CI build). */
    POSTGRESQL("PostgreSQL");

    private final String productName;

    DatabaseVendor(String productName) {
        this.productName = productName;
    }

    /**
     * Whether a JDBC {@code DatabaseMetaData.getDatabaseProductName()} value denotes this vendor.
     *
     * @param databaseProductName the product name reported by the driver
     * @return {@code true} if the product is this vendor
     */
    public boolean matches(String databaseProductName) {
        return databaseProductName.toLowerCase(Locale.ROOT).startsWith(productName.toLowerCase(Locale.ROOT));
    }
}
