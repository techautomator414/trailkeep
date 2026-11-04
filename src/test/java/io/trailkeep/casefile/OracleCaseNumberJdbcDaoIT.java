// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * The dialect-built {@code NEXTVAL} statement of {@link CaseNumberJdbcDao} on Oracle ({@code -Poracle-it}, both
 * images); {@code CaseServiceIT} covers PostgreSQL. The sequence is not pooled, so consecutive draws differ by one.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@RequiresDocker
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OracleCaseNumberJdbcDaoIT {

    @Autowired
    private CaseNumberJdbcDao numbers;

    @Test
    void consecutiveNumbersAreOneApart() {
        long first = numbers.next();

        assertThat(numbers.next()).isEqualTo(first + 1);
    }
}
