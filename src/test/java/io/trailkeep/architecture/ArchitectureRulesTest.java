// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.members;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMembers;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaMember;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.domain.JavaType;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.EvaluationResult;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import io.trailkeep.shared.access.CaseAccessPath;
import io.trailkeep.shared.access.CaseRooted;
import io.trailkeep.shared.access.CaseScope;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Architecture rules from IMPLEMENTATION_PLAN Part B and ADR-0005. Each rule is evaluated against the production
 * classes of {@code io.trailkeep}, and each has negative tests proving it fires on a planted violation in
 * {@code trailkeep.archfixtures} and stays quiet on the compliant fixtures. The rules carry no package filter of their
 * own: the imported class set decides what they govern, so the same rule applies to production code and fixtures. Rules allow an empty subject set because the classes they govern (repositories,
 * entities, controllers) arrive in later work packages; the negative tests prove they are not vacuous.
 */
class ArchitectureRulesTest {

    private static final String ROOT = "io.trailkeep";
    private static final String FIXTURES = "trailkeep.archfixtures";
    private static final String SPRING_DATA = "org.springframework.data";
    private static final String SPRING_DATA_REPOSITORY = "org.springframework.data.repository.Repository";
    private static final String ENTITY = "jakarta.persistence.Entity";
    private static final String ENUMERATED = "jakarta.persistence.Enumerated";

    static final ArchRule CASE_ROOTED_REPOSITORIES_EXTEND_ONLY_REPOSITORY = classes()
            .that()
            .areAnnotatedWith(CaseRooted.class)
            .should()
            .beInterfaces()
            .andShould()
            .beAssignableTo(SPRING_DATA_REPOSITORY)
            .andShould(extendNoSpringDataInterfaceButRepository())
            .because("inherited CRUD and executor methods would bypass the CaseScope (ADR-0005)")
            .allowEmptyShould(true);

    static final ArchRule CASE_ROOTED_METHODS_TAKE_A_SCOPE = classes()
            .that()
            .areAnnotatedWith(CaseRooted.class)
            .should(exposeOnlyMethodsWithACaseScopeParameter())
            .because("every query on a case-rooted table applies scope.toPredicate() (ADR-0005)")
            .allowEmptyShould(true);

    static final ArchRule CASE_ROOTED_INJECTED_ONLY_INTO_ACCESS_PATHS = noClasses()
            .that()
            .areNotAnnotatedWith(CaseAccessPath.class)
            .should(referenceACaseRootedRepositoryInAFieldOrParameter())
            .because("only reviewed @CaseAccessPath classes may hold case-rooted repositories (ADR-0005)")
            .allowEmptyShould(true);

    static final ArchRule NO_LOB = noMembers()
            .should()
            .beAnnotatedWith("jakarta.persistence.Lob")
            .because("@Lob maps differently on Oracle and PostgreSQL (BUILD_PLAN section 8)")
            .allowEmptyShould(true);

    static final ArchRule ENUMERATED_IS_STRING = members()
            .that()
            .areAnnotatedWith(ENUMERATED)
            .should(declareEnumTypeString())
            .because("ordinals break when constants are reordered (Part B Persistence)")
            .allowEmptyShould(true);

    static final ArchRule ENTITY_ENUM_FIELDS_ARE_ENUMERATED = fields()
            .that()
            .areDeclaredInClassesThat()
            .areAnnotatedWith(ENTITY)
            .and()
            .doNotHaveModifier(JavaModifier.STATIC)
            .and()
            .areNotAnnotatedWith("jakarta.persistence.Transient")
            .should(beEnumeratedOrConvertedIfEnumTyped())
            .because("an enum field without @Enumerated is persisted by ordinal")
            .allowEmptyShould(true);

    static final ArchRule REPOSITORIES_ARE_PACKAGE_PRIVATE = classes()
            .that()
            .areAssignableTo(SPRING_DATA_REPOSITORY)
            .should()
            .notBePublic()
            .because("modules talk through services, never through each other's repositories (Part B)")
            .allowEmptyShould(true);

    static final ArchRule NO_ENTITY_IN_WEB = noClasses()
            .that()
            .resideInAPackage("..web..")
            .should()
            .dependOnClassesThat()
            .areAnnotatedWith(ENTITY)
            .because("no entity reaches a controller or template (Part B Persistence)")
            .allowEmptyShould(true);

    static final ArchRule NO_FIELD_INJECTION = noFields()
            .should()
            .beAnnotatedWith("org.springframework.beans.factory.annotation.Autowired")
            .orShould()
            .beAnnotatedWith("org.springframework.beans.factory.annotation.Value")
            .orShould()
            .beAnnotatedWith("jakarta.inject.Inject")
            .orShould()
            .beAnnotatedWith("jakarta.annotation.Resource")
            .because("constructor injection only (Part B Language and framework)")
            .allowEmptyShould(true);

    static final ArchRule ALL_SCOPE_GRANTED_ONLY_BY_SECURITY = noClasses()
            .that()
            .resideOutsideOfPackages(ROOT + ".security..", ROOT + ".shared..")
            .should()
            .callMethod(CaseScope.class, "all")
            .because("only the security module grants the all-cases scope, after checking the role (ADR-0005)")
            .allowEmptyShould(true);

    private static final JavaClasses PRODUCTION = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void caseRootedRepositoriesExtendOnlyRepository() {
        CASE_ROOTED_REPOSITORIES_EXTEND_ONLY_REPOSITORY.check(PRODUCTION);
    }

    @Test
    void caseRootedRepositoryMethodsTakeAScope() {
        CASE_ROOTED_METHODS_TAKE_A_SCOPE.check(PRODUCTION);
    }

    @Test
    void caseRootedRepositoriesAreInjectedOnlyIntoAccessPaths() {
        CASE_ROOTED_INJECTED_ONLY_INTO_ACCESS_PATHS.check(PRODUCTION);
    }

    @Test
    void noLob() {
        NO_LOB.check(PRODUCTION);
    }

    @Test
    void enumeratedIsString() {
        ENUMERATED_IS_STRING.check(PRODUCTION);
        ENTITY_ENUM_FIELDS_ARE_ENUMERATED.check(PRODUCTION);
    }

    @Test
    void repositoriesArePackagePrivate() {
        REPOSITORIES_ARE_PACKAGE_PRIVATE.check(PRODUCTION);
    }

    @Test
    void noEntityInWeb() {
        NO_ENTITY_IN_WEB.check(PRODUCTION);
    }

    @Test
    void noFieldInjection() {
        NO_FIELD_INJECTION.check(PRODUCTION);
    }

    @Test
    void allScopeIsGrantedOnlyBySecurity() {
        ALL_SCOPE_GRANTED_ONLY_BY_SECURITY.check(PRODUCTION);
    }

    @Test
    void productionImportSeesTheApplicationButNoTestCode() {
        assertThat(PRODUCTION.contain("io.trailkeep.TrailkeepApplication")).isTrue();
        assertThat(PRODUCTION.contain("io.trailkeep.shared.access.CaseScope")).isTrue();
        assertThat(PRODUCTION.stream().map(JavaClass::getPackageName))
                .allMatch(name -> name.startsWith(ROOT))
                .noneMatch(name -> name.equals("io.trailkeep.architecture"));
    }

    /** Each rule fires on its planted violation and passes the compliant fixtures. */
    @Nested
    class RulesFireOnPlantedViolations {

        @Test
        void extendingCrudOrExecutorInterfacesIsRejected() {
            assertFires(CASE_ROOTED_REPOSITORIES_EXTEND_ONLY_REPOSITORY, "CrudCaseRepository");
            assertFires(CASE_ROOTED_REPOSITORIES_EXTEND_ONLY_REPOSITORY, "ExampleExecutorCaseRepository");
            assertPasses(CASE_ROOTED_REPOSITORIES_EXTEND_ONLY_REPOSITORY, "CompliantCaseRepository");
        }

        @Test
        void unscopedDeclaredOrFragmentMethodsAreRejected() {
            assertFires(CASE_ROOTED_METHODS_TAKE_A_SCOPE, "UnscopedMethodCaseRepository");
            assertFires(CASE_ROOTED_METHODS_TAKE_A_SCOPE, "UnscopedFragmentCaseRepository");
            assertPasses(CASE_ROOTED_METHODS_TAKE_A_SCOPE, "CompliantCaseRepository");
        }

        @Test
        void injectionOutsideAccessPathsIsRejected() {
            assertFires(CASE_ROOTED_INJECTED_ONLY_INTO_ACCESS_PATHS, "UnmarkedFieldHolder");
            assertFires(CASE_ROOTED_INJECTED_ONLY_INTO_ACCESS_PATHS, "UnmarkedConstructorHolder");
            assertFires(CASE_ROOTED_INJECTED_ONLY_INTO_ACCESS_PATHS, "UnmarkedProviderHolder");
            assertPasses(CASE_ROOTED_INJECTED_ONLY_INTO_ACCESS_PATHS, "CompliantAccessPath");
        }

        @Test
        void lobIsRejected() {
            assertFires(NO_LOB, "LobEntity");
            assertPasses(NO_LOB, "FixtureEntity");
        }

        @Test
        void ordinalAndDefaultEnumeratedAreRejected() {
            assertFires(ENUMERATED_IS_STRING, "OrdinalEntity");
            assertFires(ENUMERATED_IS_STRING, "DefaultEnumeratedEntity");
            assertPasses(ENUMERATED_IS_STRING, "FixtureEntity");
        }

        @Test
        void enumFieldsWithoutEnumeratedAreRejected() {
            assertFires(ENTITY_ENUM_FIELDS_ARE_ENUMERATED, "UnannotatedEnumEntity");
            assertPasses(ENTITY_ENUM_FIELDS_ARE_ENUMERATED, "FixtureEntity");
        }

        @Test
        void publicRepositoriesAreRejected() {
            assertFires(REPOSITORIES_ARE_PACKAGE_PRIVATE, "PublicRepository");
            assertPasses(REPOSITORIES_ARE_PACKAGE_PRIVATE, "CompliantCaseRepository");
        }

        @Test
        void entitiesInWebAreRejected() {
            assertFires(NO_ENTITY_IN_WEB, "web.EntityLeakingController");
            assertPasses(NO_ENTITY_IN_WEB, "web.GoodController");
        }

        @Test
        void fieldInjectionIsRejected() {
            assertFires(NO_FIELD_INJECTION, "FieldInjected");
            assertPasses(NO_FIELD_INJECTION, "GoodConstructorInjected");
        }

        @Test
        void allScopeOutsideSecurityIsRejected() {
            assertFires(ALL_SCOPE_GRANTED_ONLY_BY_SECURITY, "AllScopeOutsideSecurity");
            assertPasses(ALL_SCOPE_GRANTED_ONLY_BY_SECURITY, "GoodSystemScopeJob");
        }

        private void assertFires(ArchRule rule, String fixture) {
            EvaluationResult result = rule.evaluate(importFixture(fixture));
            assertThat(result.hasViolation()).as("%s fires on %s", rule.getDescription(), fixture).isTrue();
            assertThat(result.getFailureReport().getDetails())
                    .anySatisfy(detail -> assertThat(detail).contains(simpleName(fixture)));
        }

        private void assertPasses(ArchRule rule, String fixture) {
            EvaluationResult result = rule.evaluate(importFixture(fixture));
            assertThat(result.getFailureReport().getDetails())
                    .as("%s on compliant %s", rule.getDescription(), fixture)
                    .isEmpty();
        }

        private JavaClasses importFixture(String fixture) {
            return new ClassFileImporter().importClasses(load(FIXTURES + "." + fixture));
        }

        private Class<?> load(String className) {
            try {
                return Class.forName(className, false, getClass().getClassLoader());
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Missing fixture " + className, e);
            }
        }

        private String simpleName(String fixture) {
            return fixture.substring(fixture.lastIndexOf('.') + 1);
        }
    }

    private static ArchCondition<JavaClass> extendNoSpringDataInterfaceButRepository() {
        return condition("extend no Spring Data interface other than Repository", (javaClass, events) -> javaClass
                .getAllRawInterfaces()
                .stream()
                .filter(type -> type.getPackageName().startsWith(SPRING_DATA))
                .filter(type -> !type.isEquivalentTo(org.springframework.data.repository.Repository.class))
                .forEach(type -> events.add(SimpleConditionEvent.violated(
                        javaClass, javaClass.getName() + " extends " + type.getName()))));
    }

    private static ArchCondition<JavaClass> exposeOnlyMethodsWithACaseScopeParameter() {
        return condition("expose only methods with a CaseScope parameter", (javaClass, events) -> javaClass
                .getAllMethods()
                .stream()
                .filter(method -> !method.getOwner().getPackageName().startsWith(SPRING_DATA))
                .filter(method -> !method.getOwner().isEquivalentTo(Object.class))
                .filter(method -> !hasCaseScopeParameter(method))
                .forEach(method -> events.add(SimpleConditionEvent.violated(
                        method, javaClass.getName() + " exposes unscoped " + method.getFullName()))));
    }

    private static boolean hasCaseScopeParameter(JavaMethod method) {
        return method.getRawParameterTypes().stream().anyMatch(type -> type.isAssignableTo(CaseScope.class));
    }

    private static ArchCondition<JavaClass> referenceACaseRootedRepositoryInAFieldOrParameter() {
        return condition("reference a @CaseRooted repository in a field or parameter", (javaClass, events) -> {
            Stream<Reference> fieldRefs = javaClass.getFields().stream()
                    .map(field -> new Reference(field.getFullName(), field.getType()));
            Stream<Reference> parameterRefs = javaClass.getCodeUnits().stream()
                    .flatMap(unit -> unit.getParameterTypes().stream()
                            .map(type -> new Reference(unit.getFullName(), type)));
            Stream.concat(fieldRefs, parameterRefs)
                    .filter(Reference::involvesCaseRooted)
                    .forEach(ref -> events.add(SimpleConditionEvent.satisfied(
                            javaClass, javaClass.getName() + " references a @CaseRooted repository in " + ref.where())));
        });
    }

    private record Reference(String where, JavaType type) {
        boolean involvesCaseRooted() {
            return type.getAllInvolvedRawTypes().stream().anyMatch(raw -> raw.isAnnotatedWith(CaseRooted.class));
        }
    }

    private static ArchCondition<JavaMember> declareEnumTypeString() {
        return condition("declare EnumType.STRING", (member, events) -> {
            JavaAnnotation<?> enumerated = member.getAnnotationOfType(ENUMERATED);
            Optional<Object> value = enumerated.tryGetExplicitlyDeclaredProperty("value");
            boolean string = value.filter(JavaEnumConstant.class::isInstance)
                    .map(JavaEnumConstant.class::cast)
                    .filter(constant -> constant.name().equals("STRING"))
                    .isPresent();
            if (!string) {
                events.add(SimpleConditionEvent.violated(
                        member, member.getFullName() + " is @Enumerated without EnumType.STRING"));
            }
        });
    }

    private static ArchCondition<JavaField> beEnumeratedOrConvertedIfEnumTyped() {
        return condition("be @Enumerated or @Convert if enum-typed", (field, events) -> {
            boolean enumTyped = field.getRawType().isEnum();
            boolean mapped = field.isAnnotatedWith(ENUMERATED) || field.isAnnotatedWith("jakarta.persistence.Convert");
            if (enumTyped && !mapped) {
                events.add(SimpleConditionEvent.violated(
                        field, field.getFullName() + " is an enum without @Enumerated(EnumType.STRING)"));
            }
        });
    }

    private interface Check<T> {
        void accept(T item, ConditionEvents events);
    }

    private static <T> ArchCondition<T> condition(String description, Check<T> check) {
        return new ArchCondition<>(description) {
            @Override
            public void check(T item, ConditionEvents events) {
                check.accept(item, events);
            }
        };
    }

}
