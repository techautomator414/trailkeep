// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import io.trailkeep.casefile.SearchProperties.Adapter;
import io.trailkeep.shared.persistence.DatabaseVendor;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** {@code app.search.adapter} chooses the gateway, and refuses an adapter its vendor does not have. */
class SearchConfigTest {

    private final JdbcTemplate jdbc = new JdbcTemplate();

    @Test
    void likeServesEitherVendor() {
        assertThat(SearchConfig.gateway(Adapter.LIKE, DatabaseVendor.POSTGRESQL, jdbc))
                .isExactlyInstanceOf(LikeSearchGateway.class);
        assertThat(SearchConfig.gateway(Adapter.LIKE, DatabaseVendor.ORACLE, jdbc))
                .isExactlyInstanceOf(LikeSearchGateway.class);
    }

    @Test
    void oracleTextServesOracleOnly() {
        assertThat(SearchConfig.gateway(Adapter.ORACLE_TEXT, DatabaseVendor.ORACLE, jdbc))
                .isExactlyInstanceOf(OracleTextSearchGateway.class);
        assertThatIllegalStateException()
                .isThrownBy(() -> SearchConfig.gateway(Adapter.ORACLE_TEXT, DatabaseVendor.POSTGRESQL, jdbc))
                .withMessageContaining("app.search.adapter=oracle-text needs Oracle");
    }
}
