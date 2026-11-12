// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseImportService.ImportRun;
import io.trailkeep.casefile.CaseImportService.ImportedCase;
import io.trailkeep.casefile.CaseImportService.ImportedComment;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.config.AppProperties;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.ImportPeopleService;
import io.trailkeep.user.TeamRole;
import jakarta.persistence.EntityManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CaseImportService} (WP-26 part e, design §7.1, §8.1): each relaxation of R6 1 to 5 by its name, and the rules
 * that stay, on both vendors.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractCaseImportIT {

    private static final Instant OPENED = Instant.parse("2019-03-04T15:00:00Z");
    private static final Instant CLOSED = Instant.parse("2019-06-01T09:30:00Z");
    private static final ImportRun RUN = new ImportRun(77L, 88L);

    @Autowired
    private CaseImportService imports;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private AppProperties app;

    @Autowired
    private EntityManager entityManager;

    private long team;
    private long caseType;
    private long analyst;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        team = seed.team();
        caseType = seed.caseType();
        analyst = seed.user();
        seed.member(analyst, team, TeamRole.ANALYST);
    }

    @Test
    void importedCaseKeepsLegacyOpenedAt() {
        CaseNumber number = imports.importCase(RUN, open(analyst, null));

        Map<String, Object> row = caseRow(number);
        assertThat(number.year())
                .as("numbered in the load year")
                .isEqualTo(Year.now(app.timezone()).getValue());
        assertThat(instant(number, "OPENED_AT")).isEqualTo(OPENED);
        assertThat(instant(number, "CREATED_AT")).isAfter(OPENED);
        assertThat(id(row.get("CREATED_BY"))).isEqualTo(ImportPeopleService.IMPORTER_ID);
        assertThat(row.get("STATUS")).isEqualTo("OPEN");
        assertThat(row.get("LEGACY_NUMBER")).isEqualTo("LEG-2019-0042");
        assertThat(slaState(number))
                .as("the clock ran from the 2019 dates, so the case is overdue at load")
                .isEqualTo("OVERDUE");
        assertThat(searchText(number)).contains("leg-2019-0042");
    }

    @Test
    void importedCaseMayBeCreatedClosed() {
        CaseNumber number = imports.importCase(RUN, closed(analyst, caseType));

        Map<String, Object> row = caseRow(number);
        assertThat(row.get("STATUS")).isEqualTo("CLOSED");
        assertThat(instant(number, "CLOSED_AT")).isEqualTo(CLOSED);
        assertThat(jdbc.queryForMap(
                        "SELECT SLA_STATE, SLA_DUE_AT, AT_RISK_AT FROM CASE_SLA WHERE CASE_ID = ?", row.get("ID")))
                .containsEntry("SLA_STATE", "NOT_APPLICABLE")
                .containsEntry("SLA_DUE_AT", null)
                .containsEntry("AT_RISK_AT", null);
    }

    @Test
    void closedImportMayUseInactiveType() {
        jdbc.update("UPDATE CASE_TYPE SET ACTIVE = ? WHERE ID = ?", false, caseType);

        assertThat(imports.importCase(RUN, closed(analyst, caseType))).isNotNull();
        assertThatThrownBy(() -> imports.importCase(RUN, open(analyst, null))).satisfies(kind(Kind.INVALID));
    }

    @Test
    void closedImportMayBeOwnedByPrincipal() {
        CaseNumber number = imports.importCase(RUN, closed(ImportPeopleService.IMPORTER_ID, caseType));

        assertThat(id(caseRow(number).get("OWNER_ID"))).isEqualTo(ImportPeopleService.IMPORTER_ID);
        assertThatThrownBy(() -> imports.importCase(RUN, open(ImportPeopleService.IMPORTER_ID, null)))
                .as("the principal cannot own an open case")
                .satisfies(kind(Kind.INVALID));
        assertThatThrownBy(() -> imports.importCase(RUN, closed(ImportPeopleService.LEGACY_APPROVAL_ID, caseType)))
                .as("the legacy approver never owns a case, so owner and approver differ")
                .satisfies(kind(Kind.INVALID));
    }

    @Test
    void defaultOwnerApplied() {
        CaseNumber number = imports.importCase(RUN, open(analyst, "Synthetic Legacy Owner"));

        assertThat(caseRow(number).get("OWNER_NAME_SNAPSHOT")).isEqualTo("Synthetic Legacy Owner");
        long outsider = new CaseSeed(jdbc).user();
        assertThatThrownBy(() -> imports.importCase(RUN, open(outsider, "Synthetic Legacy Owner")))
                .as("a default owner must still be able to own the team's cases")
                .satisfies(kind(Kind.INVALID));
    }

    @Test
    void everythingElseStaysAsForAnyCase() {
        ImportedCase pending = new ImportedCase(
                fields(analyst, caseType), ref(), null, null, CaseStatus.PENDING_CLOSURE, OPENED, null);
        ImportedCase closedWithoutTime =
                new ImportedCase(fields(analyst, caseType), ref(), null, null, CaseStatus.CLOSED, OPENED, null);
        ImportedCase closedBeforeOpened =
                new ImportedCase(fields(analyst, caseType), ref(), null, null, CaseStatus.CLOSED, CLOSED, OPENED);
        ImportedCase future = new ImportedCase(
                fields(analyst, caseType),
                ref(),
                null,
                null,
                CaseStatus.OPEN,
                Instant.now().plusSeconds(86_400),
                null);

        for (ImportedCase refused : new ImportedCase[] {pending, closedWithoutTime, closedBeforeOpened, future}) {
            assertThatThrownBy(() -> imports.importCase(RUN, refused)).satisfies(kind(Kind.INVALID));
        }

        jdbc.update("UPDATE TEAM SET ACTIVE = ? WHERE ID = ?", false, team);
        assertThatThrownBy(() -> imports.importCase(RUN, closed(analyst, caseType)))
                .as("R6 relaxes the type of a closed case, never its team")
                .satisfies(kind(Kind.INVALID));
    }

    @Test
    void closingTimeInTheFutureIsRefused() {
        ImportedCase closesTomorrow = new ImportedCase(
                fields(analyst, caseType),
                ref(),
                null,
                null,
                CaseStatus.CLOSED,
                OPENED,
                Instant.now().plusSeconds(86_400));

        assertThatThrownBy(() -> imports.importCase(RUN, closesTomorrow))
                .satisfies(kind(Kind.INVALID))
                .hasMessageContaining("dated in the future");
    }

    @Test
    void theImportIsAuditedByThePrincipalWithTheRunAndRedactedText() {
        CaseNumber number = imports.importCase(RUN, open(analyst, null));

        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ACTOR_NAME_SNAPSHOT, ORIGIN, ACTION, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE CASE_ID = ?",
                caseRow(number).get("ID"));
        assertThat(id(event.get("ACTOR_ID"))).isEqualTo(ImportPeopleService.IMPORTER_ID);
        assertThat(event)
                .containsEntry("ACTOR_NAME_SNAPSHOT", "Trailkeep import")
                .containsEntry("ORIGIN", "IMPORT")
                .containsEntry("ACTION", "CASE_IMPORTED");
        assertThat(String.valueOf(event.get("NEW_VALUES_JSON")))
                .contains("\"runId\":77", "\"approverId\":88", "LEG-2019-0042")
                .doesNotContain("Synthetic legacy description", "Synthetic legacy subject");
    }

    @Test
    void importedCommentKeepsLegacyTimeAndAuthor() {
        CaseNumber number = imports.importCase(RUN, closed(analyst, caseType));
        Instant written = Instant.parse("2019-04-02T10:15:00Z");

        long unmatched = imports.importComment(
                RUN, new ImportedComment(number, "Synthetic legacy note", null, "Synthetic Legacy Author", written));
        final long matched = imports.importComment(
                RUN, new ImportedComment(number, "Synthetic matched note", analyst, "Seed User " + analyst, written));

        entityManager.flush();
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT AUTHOR_ID, AUTHOR_NAME_SNAPSHOT, BODY FROM CASE_COMMENT WHERE ID = ?", unmatched);
        assertThat(row)
                .containsEntry("AUTHOR_ID", null)
                .containsEntry("AUTHOR_NAME_SNAPSHOT", "Synthetic Legacy Author")
                .containsEntry("BODY", "Synthetic legacy note");
        assertThat(jdbc.queryForObject("SELECT CREATED_AT FROM CASE_COMMENT WHERE ID = ?", Timestamp.class, unmatched))
                .isEqualTo(Timestamp.from(written));
        assertThat(id(jdbc.queryForObject("SELECT AUTHOR_ID FROM CASE_COMMENT WHERE ID = ?", Long.class, matched)))
                .isEqualTo(analyst);
        assertThat(searchText(number)).contains("synthetic legacy note", "synthetic matched note");
        assertThat(String.valueOf(jdbc.queryForObject(
                        "SELECT NEW_VALUES_JSON FROM AUDIT_EVENT WHERE ENTITY_ID = ? AND ACTION = 'COMMENT_IMPORTED'"
                                + " AND ORIGIN = 'IMPORT'",
                        String.class,
                        unmatched)))
                .contains("\"runId\":77", "Synthetic Legacy Author")
                .doesNotContain("Synthetic legacy note");
    }

    /** A description over 20 000 characters continues, escaped by the importer, in a first comment (§6.2). */
    @Test
    void descriptionOverflowContinuesInAnEscapedFirstComment() {
        CaseNumber number = imports.importCase(RUN, open(analyst, null));
        String escapedTail = "\\*the rest of a long description\\* 1\\. as typed";

        long comment = imports.importComment(
                RUN,
                new ImportedComment(number, escapedTail, ImportPeopleService.IMPORTER_ID, "Trailkeep import", OPENED));

        entityManager.flush();
        assertThat(jdbc.queryForObject("SELECT BODY FROM CASE_COMMENT WHERE ID = ?", String.class, comment))
                .isEqualTo(escapedTail);
    }

    @Test
    void importedCommentsKeepTheCommentRules() {
        CaseNumber number = imports.importCase(RUN, open(analyst, null));
        CaseNumber unknown = new CaseNumber(number.prefix(), number.year(), 9_999_999L);

        for (ImportedComment refused : new ImportedComment[] {
            new ImportedComment(number, " ", null, "Synthetic Legacy Author", OPENED),
            new ImportedComment(number, "x".repeat(CaseComment.MAX_BODY + 1), null, "Synthetic Legacy Author", OPENED),
            new ImportedComment(number, "Synthetic note", null, " ", OPENED),
            new ImportedComment(
                    number, "Synthetic note", null, "Synthetic", Instant.now().plusSeconds(86_400))
        }) {
            assertThatThrownBy(() -> imports.importComment(RUN, refused)).satisfies(kind(Kind.INVALID));
        }
        assertThatThrownBy(() -> imports.importComment(
                        RUN, new ImportedComment(unknown, "Synthetic note", null, "Synthetic", OPENED)))
                .satisfies(kind(Kind.NOT_FOUND));
    }

    /** R6 6 is for imported history: a case created in Trailkeep (no legacy ref) takes no imported comment. */
    @Test
    void nativeCaseTakesNoImportedComment() {
        CaseNumber created = new CaseSeed(jdbc).caseRow(team, analyst, Sensitivity.STANDARD, CaseStatus.OPEN);

        assertThatThrownBy(() -> imports.importComment(
                        RUN, new ImportedComment(created, "Synthetic note", null, "Synthetic", OPENED)))
                .satisfies(kind(Kind.INVALID))
                .hasMessageContaining("only an imported case");
    }

    private ImportedCase open(long owner, @Nullable String ownerSnapshot) {
        return new ImportedCase(
                fields(owner, caseType), ref(), "LEG-2019-0042", ownerSnapshot, CaseStatus.OPEN, OPENED, null);
    }

    private ImportedCase closed(long owner, long type) {
        return new ImportedCase(fields(owner, type), ref(), null, null, CaseStatus.CLOSED, OPENED, CLOSED);
    }

    private CaseFields fields(long owner, long type) {
        return new CaseFields(
                type,
                "Synthetic legacy title",
                CasePriority.NORMAL,
                LocalDate.of(2019, 3, 1),
                team,
                owner,
                "Synthetic legacy subject",
                "Synthetic legacy description",
                Sensitivity.STANDARD);
    }

    private static String ref() {
        return "legacy1:" + UUID.randomUUID();
    }

    private Map<String, Object> caseRow(CaseNumber number) {
        // The service writes through JPA in the test's transaction; JDBC reads see it once flushed.
        entityManager.flush();
        return jdbc.queryForMap("SELECT * FROM CASE_RECORD WHERE CASE_NUMBER = ?", number.toString());
    }

    private String slaState(CaseNumber number) {
        entityManager.flush();
        return String.valueOf(jdbc.queryForObject(
                "SELECT S.SLA_STATE FROM CASE_SLA S JOIN CASE_RECORD C ON C.ID = S.CASE_ID WHERE C.CASE_NUMBER = ?",
                String.class,
                number.toString()));
    }

    private String searchText(CaseNumber number) {
        entityManager.flush();
        return String.valueOf(jdbc.queryForObject(
                "SELECT S.SEARCH_TEXT FROM CASE_SEARCH S JOIN CASE_RECORD C ON C.ID = S.CASE_ID"
                        + " WHERE C.CASE_NUMBER = ?",
                String.class,
                number.toString()));
    }

    /** One of the case's instants, read as a timestamp on both vendors; the column is a fixed name from this class. */
    private Instant instant(CaseNumber number, String column) {
        entityManager.flush();
        Timestamp value = jdbc.queryForObject(
                "SELECT " + column + " FROM CASE_RECORD WHERE CASE_NUMBER = ?", Timestamp.class, number.toString());
        return value == null ? Instant.EPOCH : value.toInstant();
    }

    private static long id(@Nullable Object value) {
        return ((Number) Objects.requireNonNull(value, "column")).longValue();
    }

    private static Consumer<Throwable> kind(Kind kind) {
        return e -> assertThat(((CaseException) e).kind()).isEqualTo(kind);
    }
}
