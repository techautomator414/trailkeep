// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.securityContext;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.micrometer.core.instrument.MeterRegistry;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.testsupport.WithAppUser;
import io.trailkeep.testsupport.WithAppUserSecurityContextFactory;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-person rate limits on search (the cases list, its export and the audit search) and upload (#247, #274), with
 * limits small enough to reach: three requests, then one more an hour. Over the limit the request is a 429 with {@code
 * Retry-After}, on the error page or as the htmx problem fragment, without echoing what was sent; a refused upload is
 * never scanned; another person is unaffected; each refusal is counted and logged with the person's id and the route
 * only. Buckets outlive a test's transaction, so each test uses its own people.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(
        properties = {
            "app.rate-limit.search.capacity=3",
            "app.rate-limit.search.refill=1",
            "app.rate-limit.search.window=1h",
            "app.rate-limit.upload.capacity=3",
            "app.rate-limit.upload.refill=1",
            "app.rate-limit.upload.window=1h"
        })
@AutoConfigureMockMvc
@Transactional
@ExtendWith(OutputCaptureExtension.class)
class RateLimitIT {

    static final long SEARCHER = 971_001L;
    static final long OTHER = 971_002L;
    static final long HTMX = 971_003L;
    static final long UPLOADER = 971_004L;
    static final long EXPORTER = 971_005L;
    static final long LIMITED = 971_006L;
    static final long AUDITOR = 971_007L;
    static final long AUDIT_EXPORTER = 971_008L;
    static final long TEAM = 971_100L;

    /** Text sent with a refused request; it must not come back in the response or reach the log. */
    private static final String SENT = "synthetic-needle-text";

    /** One more request an hour: the wait after the bucket is empty. */
    private static final String HOUR = "3600";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private MeterRegistry meters;

    private CaseNumber number;

    @BeforeEach
    void seed() {
        jdbc.update("INSERT INTO TEAM (ID, NAME) VALUES (?, 'Synthetic Rate Team')", TEAM);
        CaseSeed seed = new CaseSeed(jdbc);
        for (long person : new long[] {SEARCHER, OTHER, HTMX, UPLOADER, EXPORTER, LIMITED, AUDITOR, AUDIT_EXPORTER}) {
            jdbc.update(
                    "INSERT INTO APP_USER (ID, EXTERNAL_ID, DISPLAY_NAME, EMAIL, DISABLED, CREATED_AT)"
                            + " VALUES (?, ?, 'Synthetic Person', ?, FALSE, CURRENT_TIMESTAMP)",
                    person,
                    "rate-subject-" + person,
                    "user-" + person + "@example.invalid");
            seed.member(person, TEAM, TeamRole.ANALYST);
        }
        for (long auditor : new long[] {AUDITOR, AUDIT_EXPORTER}) {
            jdbc.update(
                    "INSERT INTO APP_USER_GLOBAL_ROLE (USER_ID, GLOBAL_ROLE) VALUES (?, 'COMPLIANCE_ADMIN')", auditor);
        }
        number = seed.caseRow(TEAM, UPLOADER, Sensitivity.STANDARD, CaseStatus.OPEN);
        entityManager.flush();
    }

    @Test
    @WithAppUser(id = SEARCHER, team = TEAM, teamRole = TeamRole.ANALYST)
    void theFourthSearchIsA429OnTheErrorPageWithRetryAfterAndNothingEchoed(CapturedOutput log) throws Exception {
        double before = refusals("search");
        for (int i = 0; i < 3; i++) {
            mvc.perform(search()).andExpect(status().isOk());
        }

        mvc.perform(search())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", HOUR))
                .andExpect(content().string(containsString("<h1>Too many requests</h1>")))
                .andExpect(content().string(not(containsString(SENT))));

        assertThat(refusals("search")).isEqualTo(before + 1);
        assertThat(log.getOut())
                .contains("Rate limit reached: search refused for user " + SEARCHER)
                .doesNotContain(SENT);
    }

    @Test
    @WithAppUser(id = LIMITED, team = TEAM, teamRole = TeamRole.ANALYST)
    void anotherPersonIsUnaffectedWhenOneIsLimited() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(search()).andExpect(status().isOk());
        }
        mvc.perform(search()).andExpect(status().isTooManyRequests());

        mvc.perform(search().with(securityContext(new WithAppUserSecurityContextFactory()
                        .createSecurityContext(
                                RateLimitIT.class.getDeclaredMethod("asOther").getAnnotation(WithAppUser.class)))))
                .andExpect(status().isOk());
    }

    @Test
    @WithAppUser(id = HTMX, team = TEAM, teamRole = TeamRole.ANALYST)
    void anHtmxSearchGetsThe429ProblemFragment() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(search().header("HX-Request", "true")).andExpect(status().isOk());
        }

        mvc.perform(search().header("HX-Request", "true"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", HOUR))
                .andExpect(content().string(containsString("role=\"alert\"")))
                .andExpect(content().string(containsString("Too many requests")))
                .andExpect(content().string(not(containsString("<html"))))
                .andExpect(content().string(not(containsString(SENT))));
    }

    @Test
    @WithAppUser(id = EXPORTER, team = TEAM, teamRole = TeamRole.ANALYST)
    void theCsvExportDrawsOnTheSameSearchBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(search()).andExpect(status().isOk());
        }

        mvc.perform(get("/cases/export.csv").param("q", SENT))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", HOUR));
    }

    @Test
    @WithAppUser(id = AUDITOR, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theAuditSearchDrawsOnTheSearchBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/admin/audit").param("actor", SENT)).andExpect(status().isOk());
        }

        mvc.perform(get("/admin/audit").param("actor", SENT))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", HOUR))
                .andExpect(content().string(not(containsString(SENT))));
    }

    @Test
    @WithAppUser(id = AUDIT_EXPORTER, globalRole = GlobalRole.COMPLIANCE_ADMIN)
    void theAuditExportDrawsOnTheSameSearchBucket() throws Exception {
        for (int i = 0; i < 3; i++) {
            mvc.perform(get("/admin/audit").param("actor", SENT)).andExpect(status().isOk());
        }

        mvc.perform(get("/admin/audit/export.csv").param("actor", SENT))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", HOUR));
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ACTION = 'AUDIT_EXPORTED' AND ACTOR_ID = ?",
                        Long.class,
                        AUDIT_EXPORTER))
                .as("a refused export is not audited")
                .isZero();
    }

    @Test
    @WithAppUser(id = UPLOADER, globalRole = GlobalRole.COMPLIANCE_ADMIN, team = TEAM, teamRole = TeamRole.ANALYST)
    void theFourthUploadIsRefusedBeforeItIsScanned(CapturedOutput log) throws Exception {
        double before = refusals("upload");
        // The test profile's scanner is unreachable on purpose, so an upload that is handled answers 503 "try again".
        for (int i = 0; i < 3; i++) {
            mvc.perform(upload()).andExpect(status().isServiceUnavailable());
        }

        mvc.perform(upload())
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", HOUR))
                .andExpect(content().string(containsString("<h1>Too many requests</h1>")))
                .andExpect(content().string(not(containsString(SENT))));

        assertThat(refusals("upload")).isEqualTo(before + 1);
        assertThat(log.getOut()).contains("Rate limit reached: upload refused for user " + UPLOADER);
        // Viewing the case is not limited.
        mvc.perform(get("/cases/{number}", number.toString())).andExpect(status().isOk());
    }

    /** The other person, for {@link #anotherPersonIsUnaffectedWhenOneIsLimited()}. */
    @WithAppUser(id = OTHER, team = TEAM, teamRole = TeamRole.ANALYST)
    void asOther() {
        // Never called: read reflectively for its annotation.
    }

    private static MockHttpServletRequestBuilder search() {
        return get("/cases").param("q", SENT);
    }

    private MockMultipartHttpServletRequestBuilder upload() {
        return multipart("/cases/{number}/attachments", number.toString())
                .file(new MockMultipartFile(
                        "file", SENT + ".txt", "text/plain", "synthetic".getBytes(StandardCharsets.UTF_8)))
                .with(csrf());
    }

    private double refusals(String route) {
        return meters.get("trailkeep.rate_limit.refusals")
                .tag("route", route)
                .counter()
                .count();
    }
}
