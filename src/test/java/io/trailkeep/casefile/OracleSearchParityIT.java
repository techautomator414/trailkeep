// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.OracleTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.GlobalRole;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import jakarta.persistence.EntityManager;
import java.io.StringReader;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The Oracle Text adapter against the {@code LIKE} adapter on Oracle ({@code -Poracle-it}, both images; ADR-0006,
 * amended 2026-11-23): one corpus of committed search texts, one set of searches, and every reader the scope tells
 * apart (a Compliance Admin, a team manager, the owner of a restricted case, a colleague who is not, another team's
 * analyst). For each reader and each search, the two adapters must return the same case numbers and the same count.
 * The searches include every character and reserved word with a meaning in Oracle Text queries, which must match
 * themselves. The corpus is committed, so the index itself answers; the writer's own uncommitted text, the
 * expansion limit and the one known difference (text past a token's first 255 bytes) have tests of their own.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@Tag("oracle")
@ActiveProfiles("oracle-test")
@Import(OracleTestcontainersConfiguration.class)
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OracleSearchParityIT {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-11-23T12:00:00Z"));

    /** Searches, as people type them: words, parts of words, case, punctuation, and Oracle Text's syntax. */
    private static final List<String> SEARCHES = List.of(
            "kestrel",
            "KESTREL",
            "kes",
            "estre",
            "kestrel-escalation",
            "escalation noted",
            "plover audit",
            "audit plover follow",
            "50%",
            "%",
            "_",
            "under_score",
            "ref 3fa9",
            "3fa9c1d2e4b5.",
            "c1d2",
            "heron",
            "heron note",
            "the",
            "fox quick",
            "near",
            "NEAR",
            "about",
            "ABOUT",
            "and",
            "or not",
            "within",
            "{braces}",
            "{",
            "}",
            "-",
            "a&b",
            "&",
            "|",
            "c|d",
            "$stem",
            "$",
            "?fuzzy",
            "?",
            "!soundex",
            "=equiv",
            "(paren)",
            "[x]",
            "~",
            ";",
            ",",
            ">",
            "*",
            "\\",
            "'",
            "\"",
            "café",
            "straße",
            "don't",
            "1,000",
            "000.50",
            "wren",
            "zzzznothing",
            "kestrel heron plover audit escalation noted",
            "SEED-2026");

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private CaseScopeService scopes;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<Long> users = new ArrayList<>();
    private final List<Long> cases = new ArrayList<>();
    private final List<Long> teams = new ArrayList<>();
    private CaseSeed seed;
    private TransactionTemplate readOnly;
    private CaseRecordQueries like;
    private CaseRecordQueries oracleText;
    private OracleTextJdbcDao index;
    private final Map<String, UserPermissions> readers = new LinkedHashMap<>();
    private final Map<String, CaseNumber> corpus = new LinkedHashMap<>();

    @BeforeEach
    void commitCorpus() {
        seed = new CaseSeed(jdbc);
        readOnly = new TransactionTemplate(transactionManager);
        readOnly.setReadOnly(true);
        index = new OracleTextJdbcDao(jdbc);
        like = new CaseRecordQueriesImpl(entityManager, new LikeSearchGateway());
        oracleText =
                new CaseRecordQueriesImpl(entityManager, new OracleTextSearchGateway(new LikeSearchGateway(), index));
        long teamA = team();
        long teamB = team();
        long manager = user(teamA, TeamRole.MANAGER);
        long owner = user(teamA, TeamRole.ANALYST);
        long colleague = user(teamA, TeamRole.ANALYST);
        long other = user(teamB, TeamRole.ANALYST);
        long admin = user(teamB, TeamRole.ANALYST);
        readers.put(
                "compliance admin", new UserPermissions(admin, false, Set.of(GlobalRole.COMPLIANCE_ADMIN), Map.of()));
        readers.put("team manager", permissions(manager, teamA, TeamRole.MANAGER));
        readers.put("restricted owner", permissions(owner, teamA, TeamRole.ANALYST));
        readers.put("colleague", permissions(colleague, teamA, TeamRole.ANALYST));
        readers.put("other team", permissions(other, teamB, TeamRole.ANALYST));
        add(
                "ledger",
                teamA,
                owner,
                Sensitivity.STANDARD,
                CaseStatus.OPEN,
                "Synthetic Kestrel ledger: ref 3fa9c1d2e4b5. Kestrel-escalation noted.");
        add(
                "plover",
                teamA,
                colleague,
                Sensitivity.STANDARD,
                CaseStatus.OPEN,
                "A plover audit follow-up, 50% sample of under_score items; the quick brown fox.");
        add(
                "owned restricted",
                teamA,
                owner,
                Sensitivity.RESTRICTED,
                CaseStatus.OPEN,
                "Restricted heron note about the near miss {braces} a&b c|d $stem ?fuzzy !soundex =equiv (paren)"
                        + " [x] ~ ; , > * \\ ' \" within or not");
        add(
                "other restricted",
                teamA,
                colleague,
                Sensitivity.RESTRICTED,
                CaseStatus.PENDING_CLOSURE,
                "Restricted heron note of a colleague, and an audit.");
        add("accents", teamB, other, Sensitivity.STANDARD, CaseStatus.OPEN, "Café über straße don't 1,000.50 kestrel");
        add(
                "archived",
                teamB,
                other,
                Sensitivity.STANDARD,
                CaseStatus.ARCHIVED,
                "Archived plover audit, kestrel escalation noted.");
    }

    @AfterEach
    void removeCorpus() {
        for (long caseId : cases) {
            jdbc.update("DELETE FROM CASE_SEARCH WHERE CASE_ID = ?", caseId);
            jdbc.update("DELETE FROM CASE_RECORD WHERE ID = ?", caseId);
        }
        for (long team : teams) {
            jdbc.update("DELETE FROM APP_USER_TEAM WHERE TEAM_ID = ?", team);
        }
        for (long user : users) {
            jdbc.update("DELETE FROM APP_USER WHERE ID = ?", user);
        }
        for (long team : teams) {
            jdbc.update("DELETE FROM TEAM WHERE ID = ?", team);
        }
        long caseType = seed.caseType();
        Long policy = jdbc.queryForObject("SELECT SLA_POLICY_ID FROM CASE_TYPE WHERE ID = ?", Long.class, caseType);
        jdbc.update("DELETE FROM CASE_TYPE WHERE ID = ?", caseType);
        jdbc.update("DELETE FROM SLA_POLICY WHERE ID = ?", policy);
    }

    @Test
    void everyReaderGetsTheSameCasesAndCountFromBothAdaptersForEverySearch() {
        assertSoftly(soft -> readers.forEach((reader, permissions) -> {
            CaseScope scope = scopes.forUser(permissions);
            for (String search : SEARCHES) {
                soft.assertThat(numbers(oracleText, scope, search))
                        .as(reader + " searching " + search)
                        .isEqualTo(numbers(like, scope, search));
                soft.assertThat(count(oracleText, scope, search))
                        .as(reader + " counting " + search)
                        .isEqualTo(count(like, scope, search));
            }
        }));
    }

    @Test
    void theIndexAnswersWordsAndPartsOfWordsWithinTheScope() {
        CaseScope owner = scope("restricted owner");
        CaseScope colleague = scope("colleague");
        CaseScope manager = scope("team manager");
        CaseScope other = scope("other team");
        assertThat(numbers(oracleText, owner, "estre")).containsExactly(name("ledger"));
        assertThat(numbers(oracleText, other, "kestrel")).containsExactly(name("accents"));
        assertThat(numbers(oracleText, owner, "heron")).containsExactly(name("owned restricted"));
        assertThat(numbers(oracleText, colleague, "heron")).containsExactly(name("other restricted"));
        assertThat(numbers(oracleText, manager, "heron"))
                .containsExactlyInAnyOrder(name("owned restricted"), name("other restricted"));
        assertThat(numbers(oracleText, other, "heron")).isEmpty();
        assertThat(numbers(oracleText, owner, "{braces} a&b $stem ?fuzzy")).containsExactly(name("owned restricted"));
        assertThat(OracleTextQuery.of(LikeSearchGateway.words("estre")).contains())
                .isEqualTo("%estre%");
        assertThat(index.answers("%estre%")).isTrue();
    }

    @Test
    void theWritersOwnUncommittedTextIsFoundInItsTransaction() {
        CaseScope owner = scope("restricted owner");
        long caseId = seed.caseId(Objects.requireNonNull(corpus.get("ledger")));
        Set<String> found = Objects.requireNonNull(new TransactionTemplate(transactionManager).execute(status -> {
            jdbc.update(
                    "UPDATE CASE_SEARCH SET SEARCH_TEXT = SEARCH_TEXT || ? WHERE CASE_ID = ?",
                    CaseSearch.normalized(" godwit appended"),
                    caseId);
            Set<String> numbers = numbers(oracleText, owner, "godwit");
            status.setRollbackOnly();
            return numbers;
        }));
        assertThat(found).containsExactly(name("ledger"));
        assertThat(numbers(oracleText, owner, "godwit")).isEmpty();
    }

    @Test
    void aWordExpandingToTooManyTokensFallsBackToLike() {
        String tokens = IntStream.rangeClosed(0, 20_000)
                .mapToObj(i -> String.format("qzx%05d", i))
                .collect(Collectors.joining(" "));
        add("many tokens", teams.get(1), users.get(3), Sensitivity.STANDARD, CaseStatus.OPEN, tokens);
        CaseScope other = scope("other team");

        assertThat(index.answers("%qzx%")).as("DRG-51030 is recognised").isFalse();
        assertThat(numbers(oracleText, other, "qzx")).containsExactly(name("many tokens"));
        assertThat(numbers(oracleText, other, "qzx kestrel")).isEqualTo(numbers(like, other, "qzx kestrel"));
    }

    @Test
    void textPastATokensFirst255BytesIsTheOneKnownDifference() {
        add(
                "long token",
                teams.get(1),
                users.get(3),
                Sensitivity.STANDARD,
                CaseStatus.OPEN,
                "x".repeat(300) + "tailword and a short tailword-less end");
        CaseScope other = scope("other team");

        assertThat(numbers(like, other, "xxxtailword")).containsExactly(name("long token"));
        assertThat(numbers(oracleText, other, "xxxtailword")).isEmpty();
        assertThat(numbers(oracleText, other, "tailword")).containsExactly(name("long token"));
    }

    private void add(String name, long team, long owner, Sensitivity sensitivity, CaseStatus status, String text) {
        CaseNumber number = seed.caseRow(team, owner, sensitivity, status);
        long caseId = seed.caseId(number);
        String stored = CaseSearch.normalized(text);
        jdbc.update("INSERT INTO CASE_SEARCH (CASE_ID, SEARCH_TEXT, UPDATED_AT) VALUES (?, ?, ?)", statement -> {
            statement.setLong(1, caseId);
            statement.setClob(2, new StringReader(stored), stored.length());
            statement.setTimestamp(3, NOW);
        });
        cases.add(caseId);
        corpus.put(name, number);
    }

    private Set<String> numbers(CaseRecordQueries queries, CaseScope scope, String search) {
        return Objects.requireNonNull(readOnly.execute(status -> queries.list(scope, query(search), 1000).stream()
                .map(CaseListRow::caseNumber)
                .collect(Collectors.toCollection(TreeSet::new))));
    }

    private long count(CaseRecordQueries queries, CaseScope scope, String search) {
        return Objects.requireNonNull(
                readOnly.execute(status -> queries.count(scope, query(search).filter())));
    }

    private static CaseListQuery query(String search) {
        return new CaseListQuery(FilterBuilder.filter().text(search).build(), CaseSort.OPENED, true, 1);
    }

    private CaseScope scope(String reader) {
        return scopes.forUser(Objects.requireNonNull(readers.get(reader), reader));
    }

    private String name(String name) {
        return Objects.requireNonNull(corpus.get(name), name).toString();
    }

    private long team() {
        long team = seed.team();
        teams.add(team);
        return team;
    }

    private long user(long team, TeamRole role) {
        long user = seed.user();
        seed.member(user, team, role);
        users.add(user);
        return user;
    }

    private static UserPermissions permissions(long user, long team, TeamRole role) {
        return new UserPermissions(user, false, Set.of(), Map.of(team, role));
    }
}
