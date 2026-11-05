// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static org.assertj.core.api.Assertions.assertThat;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * The {@code LIKE} search adapter behind {@link SearchGateway}, once per vendor (ADR-0006): every word must appear in
 * the title, description or comments, ignoring case; the subject is never searched; {@code %} and {@code _} match
 * themselves; a case number matches by substring; and a restricted case that matches is absent from the rows and the
 * count of someone who may not read it.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
abstract class AbstractCaseListSearchIT {

    @Autowired
    private CaseListService list;

    @Autowired
    private CaseService cases;

    @Autowired
    private CaseCommentService comments;

    @Autowired
    private JdbcTemplate jdbc;

    private CaseSeed seed;
    private long team;
    private Actor owner;
    private Actor colleague;

    @BeforeEach
    void seed() {
        seed = new CaseSeed(jdbc);
        team = seed.team();
        owner = analyst();
        colleague = analyst();
    }

    @Test
    void everyWordMustAppearInTitleDescriptionOrCommentsIgnoringCaseButNeverInTheSubject() {
        CaseNumber both = create("Synthetic Kestrel ledger", "a Plover note", Sensitivity.STANDARD);
        CaseNumber oneWord = create("Synthetic kestrel only", "nothing else", Sensitivity.STANDARD);
        CaseNumber commented = create("Synthetic quiet case", "nothing else", Sensitivity.STANDARD);
        comments.post(owner, commented.toString(), "A comment naming a KESTREL and a plover");

        assertThat(search(owner, "kestrel PLOVER")).containsExactlyInAnyOrder(both, commented);
        assertThat(search(owner, "kestrel")).containsExactlyInAnyOrder(both, oneWord, commented);
        assertThat(search(owner, "Synthetic subject")).isEmpty();
        assertThat(search(owner, "wren")).isEmpty();
    }

    @Test
    void wildcardsMatchThemselvesAndCaseNumbersMatchBySubstring() {
        CaseNumber percent = create("Synthetic 50% sample", "under_score", Sensitivity.STANDARD);
        CaseNumber plain = create("Synthetic 50 sample", "underscore", Sensitivity.STANDARD);

        assertThat(search(owner, "50%")).containsExactly(percent);
        assertThat(search(owner, "under_score")).containsExactly(percent);
        assertThat(search(owner, "%")).containsExactly(percent);
        String number = plain.toString();
        assertThat(search(owner, number.substring(number.length() - 8).toLowerCase(Locale.ROOT)))
                .containsExactly(plain);
    }

    @Test
    void matchingRestrictedCasesAreAbsentFromRowsAndCountForSomeoneWhoMayNotReadThem() {
        CaseNumber restricted = create("Synthetic heron restricted", "heron", Sensitivity.RESTRICTED);
        CaseNumber standard = create("Synthetic heron standard", "heron", Sensitivity.STANDARD);

        CaseList seen = list.list(colleague, query("heron"));
        assertThat(seen.total()).isEqualTo(1);
        assertThat(seen.rows()).extracting(CaseList.Row::number).containsExactly(standard);
        assertThat(search(colleague, restricted.toString())).isEmpty();
        assertThat(search(owner, "heron")).containsExactlyInAnyOrder(restricted, standard);
    }

    private List<CaseNumber> search(Actor actor, String text) {
        CaseList page = list.list(actor, query(text));
        assertThat(page.total()).isEqualTo(page.rows().size());
        return page.rows().stream().map(CaseList.Row::number).toList();
    }

    private static CaseListQuery query(String text) {
        return new CaseListQuery(new CaseFilter(Set.of(), null, null, null, false, text), CaseSort.OPENED, true, 1);
    }

    private CaseNumber create(String title, String description, Sensitivity sensitivity) {
        return cases.create(
                owner,
                new CaseFields(
                        seed.caseType(),
                        title,
                        CasePriority.NORMAL,
                        LocalDate.now(ZoneOffset.UTC),
                        team,
                        owner.permissions().userId(),
                        "Synthetic subject wren",
                        description,
                        sensitivity));
    }

    private Actor analyst() {
        long user = seed.user();
        seed.member(user, team, TeamRole.ANALYST);
        return new Actor(
                new UserPermissions(user, false, Set.of(), Map.of(team, TeamRole.ANALYST)), "Synthetic Searcher");
    }
}
