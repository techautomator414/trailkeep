// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import io.trailkeep.casefile.CaseFilter;
import io.trailkeep.casefile.CaseListQuery;
import io.trailkeep.casefile.CaseScopeService;
import io.trailkeep.shared.CaseNumber;
import io.trailkeep.shared.CaseStatus;
import io.trailkeep.shared.Sensitivity;
import io.trailkeep.shared.access.CaseRooted;
import io.trailkeep.shared.access.CaseScope;
import io.trailkeep.testsupport.CaseSeed;
import io.trailkeep.testsupport.PostgresTestcontainersConfiguration;
import io.trailkeep.testsupport.RequiresDocker;
import io.trailkeep.user.TeamRole;
import io.trailkeep.user.UserPermissions;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Every declared method of every {@code @CaseRooted} repository, found by scanning production code, answers an
 * unreadable case exactly as it answers an absent one (WP-11 acceptance, ADR-0005). A new repository is covered
 * without touching this test; a method with a parameter type this test cannot supply fails it until it learns one.
 * A method that takes no case, such as the cases list's count, page and filter choices (with an empty filter and the
 * default query), is asked before the cases exist and again after: someone who may not read them gets the same answer
 * both times, so the unreadable cases are exactly as if absent, while the owner's answer changes.
 */
// "IT" is the Failsafe suffix Part B mandates for integration tests, not an abbreviation.
@SuppressWarnings("checkstyle:AbbreviationAsWordInName")
@RequiresDocker
@ActiveProfiles("test")
@Import(PostgresTestcontainersConfiguration.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Transactional
class CaseRootedInvisibilityIT {

    private static final int LIMIT = 50;
    private static final long ABSENT_ID = -42;

    @Autowired
    private ApplicationContext context;

    @Autowired
    private CaseScopeService scopes;

    @Autowired
    private JdbcTemplate jdbc;

    /** One way to call a method: who asks, about which case. */
    private record Call(CaseScope scope, CaseNumber caseNumber, long caseId) {}

    /** A method together with the repository bean type it is reached through. */
    private record Rooted(Class<?> repository, Method method) {}

    @Test
    void unreadableAndAbsentCasesAreIndistinguishableThroughEveryCaseRootedMethod() throws Exception {
        CaseSeed seed = new CaseSeed(jdbc);
        long team = seed.team();
        long owner = seed.user();
        long colleague = seed.user();
        CaseScope ownerScope = scopes.forUser(analyst(owner, team));
        CaseScope colleagueScope = scopes.forUser(analyst(colleague, team));
        CaseNumber absent = new CaseNumber("SEED", 2026, 1);
        List<Rooted> methods = caseRootedMethods();
        final Map<Method, String> colleagueBefore =
                caseLessOutcomes(methods, new Call(colleagueScope, absent, ABSENT_ID));
        final Map<Method, String> ownerBefore = caseLessOutcomes(methods, new Call(ownerScope, absent, ABSENT_ID));
        CaseNumber restricted = seeded(seed, team, owner, Sensitivity.RESTRICTED, "on the restricted case", "CLEAN");
        long restrictedId = seed.caseId(restricted);
        CaseNumber otherTeam = seeded(seed, seed.team(), owner, Sensitivity.STANDARD, "in another team", "QUARANTINED");
        long otherTeamId = seed.caseId(otherTeam);

        assertThat(methods).as("case-rooted repository methods").isNotEmpty();
        assertThat(methods)
                .extracting(rooted -> rooted.repository().getSimpleName())
                .as("repositories found by the scan")
                .contains(
                        "CaseRecordRepository",
                        "CaseCommentRepository",
                        "CaseCommentPinRepository",
                        "CaseAttachmentRepository");
        int distinguishing = 0;
        for (Rooted rooted : methods) {
            Method method = rooted.method();
            Object bean = context.getBean(rooted.repository());
            if (takesNoCase(method)) {
                assertThat(outcome(bean, method, new Call(colleagueScope, restricted, restrictedId)))
                        .as(method.toGenericString() + " after unreadable cases were added")
                        .isEqualTo(colleagueBefore.get(method));
                String ownerAfter = outcome(bean, method, new Call(ownerScope, restricted, restrictedId));
                distinguishing += ownerAfter.equals(ownerBefore.get(method)) ? 0 : 1;
                continue;
            }
            String unreadable = outcome(bean, method, new Call(colleagueScope, restricted, restrictedId));
            String outsideTeams = outcome(bean, method, new Call(colleagueScope, otherTeam, otherTeamId));
            String absentForColleague = outcome(bean, method, new Call(colleagueScope, absent, ABSENT_ID));
            String absentForOwner = outcome(bean, method, new Call(ownerScope, absent, ABSENT_ID));
            assertThat(unreadable)
                    .as(method.toGenericString())
                    .isEqualTo(outsideTeams)
                    .isEqualTo(absentForColleague)
                    .isEqualTo(absentForOwner);
            if (!outcome(bean, method, new Call(ownerScope, restricted, restrictedId))
                    .equals(unreadable)) {
                distinguishing++;
            }
        }
        assertThat(distinguishing)
                .as("methods where the owner's readable case differs (not vacuous)")
                .isPositive();
    }

    /** An open case with a pinned comment, an attachment and, like every case the application creates, its SLA row. */
    private CaseNumber seeded(
            CaseSeed seed, long team, long owner, Sensitivity sensitivity, String where, String scanStatus) {
        CaseNumber number = seed.caseRow(team, owner, sensitivity, CaseStatus.OPEN);
        long id = seed.caseId(number);
        seed.pin(seed.comment(id, owner, "A synthetic comment " + where), id, owner);
        seed.attachment(id, owner, "synthetic-" + scanStatus.toLowerCase(Locale.ROOT) + ".txt", scanStatus);
        jdbc.update("INSERT INTO CASE_SLA (CASE_ID, SLA_STATE) VALUES (?, 'NOT_APPLICABLE')", id);
        return number;
    }

    private Map<Method, String> caseLessOutcomes(List<Rooted> methods, Call call) throws IllegalAccessException {
        Map<Method, String> outcomes = new HashMap<>();
        for (Rooted rooted : methods) {
            if (takesNoCase(rooted.method())) {
                outcomes.put(rooted.method(), outcome(context.getBean(rooted.repository()), rooted.method(), call));
            }
        }
        return outcomes;
    }

    /** Whether a method names no case, so that its answer is about every case its scope reads. */
    private static boolean takesNoCase(Method method) {
        return Arrays.stream(method.getParameterTypes())
                .noneMatch(type -> type == CaseNumber.class || type == long.class || type == Long.class);
    }

    private static List<Rooted> caseRootedMethods() throws ClassNotFoundException {
        List<Rooted> methods = new ArrayList<>();
        for (JavaClass repository : new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.trailkeep")
                .that(DescribedPredicate.describe("@CaseRooted", c -> c.isAnnotatedWith(CaseRooted.class)))) {
            Class<?> type = Class.forName(repository.getName());
            for (Method method : type.getMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) && !method.isDefault()) {
                    methods.add(new Rooted(type, method));
                }
            }
        }
        return methods;
    }

    /** Calls the method and reduces the answer to what a caller could observe about the case. */
    private static String outcome(Object bean, Method method, Call call) throws IllegalAccessException {
        Object[] args = Arrays.stream(method.getParameters())
                .map(parameter -> argument(parameter.getType(), call, method))
                .toArray();
        method.setAccessible(true);
        try {
            return describe(method.invoke(bean, args));
        } catch (InvocationTargetException thrown) {
            return "threw "
                    + Objects.requireNonNullElse(thrown.getCause(), thrown)
                            .getClass()
                            .getName();
        }
    }

    private static Object argument(Class<?> type, Call call, Method method) {
        if (type == CaseScope.class) {
            return call.scope();
        }
        if (type == CaseNumber.class) {
            return call.caseNumber();
        }
        if (type == long.class || type == Long.class) {
            return call.caseId();
        }
        if (type == Collection.class) {
            // Case ids, such as the cases a list in another module names.
            return List.of(call.caseId());
        }
        return fixedArgument(type, method);
    }

    /** A value that does not depend on the call: a page size, an empty action filter, no filter, the default query. */
    private static Object fixedArgument(Class<?> type, Method method) {
        if (type == int.class || type == Integer.class) {
            return LIMIT;
        }
        if (type == Set.class) {
            // An action filter that keeps everything.
            return Set.of();
        }
        if (type == CaseFilter.class) {
            return CaseFilter.none();
        }
        if (type == CaseListQuery.class) {
            return CaseListQuery.parse(Map.of());
        }
        if (type == LocalDate.class) {
            // The day the reports' ages count to: a few days after CaseSeed's identified-on date.
            return LocalDate.of(2026, 11, 7);
        }
        if (type == Instant.class) {
            // The compliance period's bounds; an empty period still counts the open cases.
            return Instant.parse("2026-11-07T00:00:00Z");
        }
        return fail("Teach CaseRootedInvisibilityIT a value for " + type.getName() + " in " + method);
    }

    private static String describe(@Nullable Object result) {
        if (result instanceof Optional<?> optional) {
            return optional.isPresent() ? "present" : "empty";
        }
        if (result instanceof Collection<?> collection) {
            return "size " + collection.size();
        }
        return String.valueOf(result);
    }

    private static UserPermissions analyst(long userId, long team) {
        return new UserPermissions(userId, false, Set.of(), Map.of(team, TeamRole.ANALYST));
    }
}
