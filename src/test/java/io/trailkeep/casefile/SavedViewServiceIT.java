// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.casefile;

import static io.trailkeep.casefile.FilterBuilder.filter;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.trailkeep.casefile.CaseService.Actor;
import io.trailkeep.casefile.SavedViewService.Created;
import io.trailkeep.casefile.SavedViewService.Problem;
import io.trailkeep.casefile.SavedViewService.Refused;
import io.trailkeep.casefile.SavedViewService.Saved;
import io.trailkeep.casefile.SavedViewService.View;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.DomainException.Kind;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.UserPermissions;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Saved views on PostgreSQL (WP-15 part 3): a view stores the canonical query of the filter and order on page 1 under
 * a stripped, per-person unique name; blank, over-long and duplicate names and the plain list are refused; a person
 * lists only their own views, by name, and deletes only their own (another person's view is "not found"); saving and
 * deleting are audited by name only.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class SavedViewServiceIT {

    @Autowired
    private SavedViewService views;

    @Autowired
    private JdbcTemplate jdbc;

    private Actor owner;
    private Actor other;

    @BeforeEach
    void seed() {
        CaseSeed seed = new CaseSeed(jdbc);
        owner = actor(seed.user(), "Synthetic View Owner");
        other = actor(seed.user(), "Synthetic Other Person");
    }

    @Test
    void viewsStoreTheCanonicalQueryOnPageOneUnderTheStrippedNameAndAreAudited() {
        CaseListQuery query = new CaseListQuery(
                filter().statuses(CaseStatus.CLOSED, CaseStatus.OPEN)
                        .text("heron")
                        .build(),
                CaseSort.TITLE,
                false,
                3);

        View view = created(views.save(owner, "  Open herons  ", query));

        assertThat(view.name()).isEqualTo("Open herons");
        assertThat(view.query()).isEqualTo("q=heron&status=OPEN&status=CLOSED&sort=title&dir=asc");
        assertThat(CaseListQuery.parse(Map.of(
                        "q", List.of("heron"),
                        "status", List.of("OPEN", "CLOSED"),
                        "sort", List.of("title"),
                        "dir", List.of("asc"))))
                .isEqualTo(query.onPage(1));
        assertThat(views.views(userId(owner))).containsExactly(view);
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, CASE_ID, ENTITY_TYPE, ENTITY_ID, ACTION, OLD_VALUES_JSON, NEW_VALUES_JSON"
                        + " FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'SAVED_VIEW' AND ENTITY_ID = ?",
                view.id());
        assertThat(event)
                .containsEntry("ACTOR_ID", userId(owner))
                .containsEntry("ACTION", "SAVED_VIEW_CREATED")
                .containsEntry("CASE_ID", null)
                .containsEntry("OLD_VALUES_JSON", null);
        assertThat((String) event.get("NEW_VALUES_JSON")).contains("\"name\":\"Open herons\"");
    }

    @Test
    void blankLongDuplicateNamesAndThePlainListAreRefusedButAnotherPersonMayReuseAName() {
        CaseListQuery open = query(filter().statuses(CaseStatus.OPEN));
        created(views.save(owner, "Mine", open));

        assertThat(refused(views.save(owner, "   ", open))).isEqualTo(Problem.NAME_BLANK);
        assertThat(refused(views.save(owner, "x".repeat(101), open))).isEqualTo(Problem.NAME_TOO_LONG);
        assertThat(refused(views.save(owner, " Mine ", open))).isEqualTo(Problem.NAME_TAKEN);
        assertThat(refused(views.save(owner, "Everything", query(filter())))).isEqualTo(Problem.NOTHING_TO_SAVE);
        assertThat(refused(views.save(
                        owner,
                        "Long",
                        query(filter().text(Character.toString(0x20AC).repeat(CaseFilter.MAX_TEXT))))))
                .isEqualTo(Problem.QUERY_TOO_LONG);
        assertThat(created(views.save(owner, "x".repeat(100), open)).name()).hasSize(100);
        assertThat(created(views.save(other, "Mine", open)).name()).isEqualTo("Mine");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM SAVED_VIEW WHERE USER_ID = ?", Long.class, userId(owner)))
                .isEqualTo(2);
    }

    @Test
    void peopleListTheirOwnViewsByNameAndDeleteOnlyTheirOwn() {
        View bravo = created(views.save(owner, "bravo", query(filter().statuses(CaseStatus.OPEN))));
        View alpha = created(views.save(owner, "Alpha", query(filter().statuses(CaseStatus.CLOSED))));
        View others = created(views.save(other, "Another person's", query(filter().text("heron"))));

        assertThat(views.views(userId(owner))).containsExactly(alpha, bravo);
        assertThat(views.views(userId(other))).containsExactly(others);

        assertThatThrownBy(() -> views.delete(owner, others.id()))
                .isInstanceOfSatisfying(
                        CaseException.class,
                        refused -> assertThat(refused.kind()).isEqualTo(Kind.NOT_FOUND));
        assertThatThrownBy(() -> views.delete(owner, -42))
                .isInstanceOfSatisfying(
                        CaseException.class,
                        refused -> assertThat(refused.kind()).isEqualTo(Kind.NOT_FOUND));
        assertThat(views.views(userId(other))).containsExactly(others);

        views.delete(owner, alpha.id());
        assertThat(views.views(userId(owner))).containsExactly(bravo);
        Map<String, Object> event = jdbc.queryForMap(
                "SELECT ACTOR_ID, ACTION, OLD_VALUES_JSON, NEW_VALUES_JSON FROM AUDIT_EVENT"
                        + " WHERE ENTITY_TYPE = 'SAVED_VIEW' AND ACTION = 'SAVED_VIEW_DELETED' AND ENTITY_ID = ?",
                alpha.id());
        assertThat(event).containsEntry("ACTOR_ID", userId(owner)).containsEntry("NEW_VALUES_JSON", null);
        assertThat((String) event.get("OLD_VALUES_JSON")).contains("\"name\":\"Alpha\"");
        assertThat(jdbc.queryForObject(
                        "SELECT COUNT(*) FROM AUDIT_EVENT WHERE ENTITY_TYPE = 'SAVED_VIEW' AND ACTION = ?",
                        Long.class,
                        "SAVED_VIEW_DELETED"))
                .isEqualTo(1);
    }

    private static CaseListQuery query(FilterBuilder filter) {
        return new CaseListQuery(filter.build(), CaseSort.OPENED, true, 1);
    }

    private static View created(Saved saved) {
        assertThat(saved).isInstanceOf(Created.class);
        return ((Created) saved).view();
    }

    private static Problem refused(Saved saved) {
        assertThat(saved).isInstanceOf(Refused.class);
        return ((Refused) saved).problem();
    }

    private static long userId(Actor actor) {
        return actor.permissions().userId();
    }

    private static Actor actor(long user, String name) {
        return new Actor(new UserPermissions(user, false, Set.of(), Map.of()), name);
    }
}
