// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.Test;

/**
 * Only the classes that read a source may depend on {@code SourceQueryJdbcDao}, the one class that runs operator-written
 * SQL on a source connection (ADR-0014, Part B "Persistence" amendment; #259): {@code JdbcSource} (extract), {@code
 * SourceTestFetch} ({@code validate-mapping}'s test fetch, which also connects) and {@code SourceAccount}, which
 * resolves the account both use. Checked on the production classes and proven to fire on a planted caller. The DAO is
 * package-private, so the rule names classes rather than referencing them.
 */
class SourceQueryDaoRulesTest {

    private static final String DAO = "io\\.trailkeep\\.importer\\.SourceQueryJdbcDao(\\$.*)?";

    private static final String READERS =
            "io\\.trailkeep\\.importer\\.(SourceQueryJdbcDao|JdbcSource|SourceTestFetch|SourceAccount)(\\$.*)?";

    static final ArchRule ONLY_SOURCE_READERS_USE_THE_SOURCE_DAO = noClasses()
            .that()
            .haveNameNotMatching(READERS)
            .should()
            .dependOnClassesThat()
            .haveNameMatching(DAO)
            .because("only extract and validate-mapping's test fetch open a source connection (ADR-0014 rule 1)");

    @Test
    void onlyExtractAndTheTestFetchUseTheSourceDao() {
        JavaClasses production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.trailkeep");

        assertThat(production.stream().anyMatch(type -> type.getName().matches(DAO)))
                .as("the DAO is among the imported classes, so the rule is not vacuous")
                .isTrue();
        ONLY_SOURCE_READERS_USE_THE_SOURCE_DAO.check(production);
    }

    @Test
    void theRuleFiresOnAnotherImporterClassThatUsesIt() throws ClassNotFoundException {
        JavaClasses fixture =
                new ClassFileImporter().importClasses(Class.forName("io.trailkeep.importer.SourceDaoOutsideExtract"));

        EvaluationResult result = ONLY_SOURCE_READERS_USE_THE_SOURCE_DAO.evaluate(fixture);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(
                        detail -> assertThat(detail).contains("SourceDaoOutsideExtract", "SourceQueryJdbcDao.open"));
    }
}
