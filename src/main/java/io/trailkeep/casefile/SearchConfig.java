// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import io.trailkeep.shared.persistence.DatabaseProperties;
import io.trailkeep.shared.persistence.DatabaseVendor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Chooses the {@link SearchGateway} named by {@code app.search.adapter} (ADR-0006, amended 2026-11-23). */
@Configuration(proxyBeanMethods = false)
class SearchConfig {

    @Bean
    SearchGateway searchGateway(SearchProperties search, DatabaseProperties database, JdbcTemplate jdbc) {
        return gateway(search.adapter(), database.vendor(), jdbc);
    }

    /**
     * The gateway for an adapter on a vendor.
     *
     * @param adapter the configured adapter
     * @param vendor the database vendor
     * @param jdbc for the Oracle Text gateway's probe
     * @return the gateway
     * @throws IllegalStateException if the adapter does not exist on the vendor, so start-up fails
     */
    static SearchGateway gateway(SearchProperties.Adapter adapter, DatabaseVendor vendor, JdbcTemplate jdbc) {
        return switch (adapter) {
            case LIKE -> new LikeSearchGateway();
            case ORACLE_TEXT -> {
                if (vendor != DatabaseVendor.ORACLE) {
                    throw new IllegalStateException(
                            "app.search.adapter=oracle-text needs Oracle; on " + vendor + " use like");
                }
                yield new OracleTextSearchGateway(new LikeSearchGateway(), new OracleTextJdbcDao(jdbc));
            }
        };
    }
}
