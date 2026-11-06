// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.document;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.user.TeamRole;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The size shown beside an attachment picks its unit from the rounded value, so a size just under a unit boundary never
 * reads "1024.0 KB" or "1024.0 MB" (#67, item 11): bytes are whole below 1 KiB, then KB, MB and GB with one decimal.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class AttachmentSizeDisplayIT {

    private static final long BASE = 966_000L;
    private static final long READER = BASE + 2;
    private static final long TEAM = BASE + 100;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AttachmentContentJdbcDao content;

    private AttachmentFixture fixture;

    @BeforeEach
    void seed() {
        fixture = new AttachmentFixture(jdbc, content, BASE);
    }

    @AfterEach
    void remove() {
        fixture.remove();
    }

    @ParameterizedTest(name = "{0} bytes is shown as {1}")
    @CsvSource({
        "512,'512 bytes'",
        "1023,'1,023 bytes'",
        "1024,'1.0 KB'",
        "1048524,'1023.9 KB'",
        "1048525,'1.0 MB'",
        "1048575,'1.0 MB'",
        "1048576,'1.0 MB'",
        "5452595,'5.2 MB'",
        "1073689395,'1023.9 MB'",
        "1073689396,'1.0 GB'",
        "2147483648,'2.0 GB'"
    })
    @WithAppUser(id = READER, team = TEAM, teamRole = TeamRole.READER)
    void theSizeUsesTheUnitOfItsRoundedValue(long sizeBytes, String shown) throws Exception {
        fixture.attachment(
                fixture.number,
                "synthetic-size.txt",
                ScanStatus.CLEAN,
                sizeBytes,
                "0".repeat(64),
                new ByteArrayInputStream(new byte[] {'x'}));

        mvc.perform(get("/cases/" + fixture.number))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString(">" + shown + "<")))
                .andExpect(content().string(not(containsString("1024.0 KB"))))
                .andExpect(content().string(not(containsString("1024.0 MB"))));
    }
}
