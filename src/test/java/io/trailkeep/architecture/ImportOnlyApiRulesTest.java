// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Charlie Genova and contributors
package io.trailkeep.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import io.trailkeep.audit.ImportedHistoryService;
import io.trailkeep.casefile.CaseImportService;
import io.trailkeep.closure.ClosureImportService;
import io.trailkeep.document.AttachmentImportService;
import org.junit.jupiter.api.Test;

/**
 * The import-only APIs relax rules for imports alone (docs/design/importer.md §8.1, R6), so only the {@code importer}
 * module may call them, besides the module that implements each (and only that one: {@code closure} may not call the
 * attachment API). One rule per API, checked together on the production classes and proven to fire on a planted
 * caller in {@code trailkeep.archfixtures}. Each later import-only API adds its line here.
 */
class ImportOnlyApiRulesTest {

    static final ArchRule IMPORT_APIS_CALLED_ONLY_BY_THE_IMPORTER = CompositeArchRule.of(
                    onlyTheImporterAnd("io.trailkeep.document..", AttachmentImportService.class))
            .and(onlyTheImporterAnd("io.trailkeep.closure..", ClosureImportService.class))
            .and(onlyTheImporterAnd("io.trailkeep.casefile..", CaseImportService.class))
            .and(onlyTheImporterAnd("io.trailkeep.audit..", ImportedHistoryService.class));

    private static ArchRule onlyTheImporterAnd(String module, Class<?> api) {
        return noClasses()
                .that()
                .resideOutsideOfPackages("io.trailkeep.importer..", module)
                .should()
                .dependOnClassesThat()
                .areAssignableTo(api)
                .because("the import-only APIs relax Trailkeep's rules for imports alone (design §8.1, R6)");
    }

    @Test
    void onlyTheImporterUsesTheImportOnlyApis() {
        IMPORT_APIS_CALLED_ONLY_BY_THE_IMPORTER.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("io.trailkeep"));
    }

    @Test
    void theRuleFiresOnCallersOutsideTheImporter() throws ClassNotFoundException {
        JavaClasses fixture =
                new ClassFileImporter().importClasses(Class.forName("trailkeep.archfixtures.ImportApiOutsideImporter"));

        EvaluationResult result = IMPORT_APIS_CALLED_ONLY_BY_THE_IMPORTER.evaluate(fixture);

        assertThat(result.hasViolation()).isTrue();
        assertThat(result.getFailureReport().getDetails())
                .anySatisfy(
                        detail -> assertThat(detail).contains("ImportApiOutsideImporter", "AttachmentImportService"))
                .anySatisfy(detail -> assertThat(detail).contains("ImportApiOutsideImporter", "ClosureImportService"))
                .anySatisfy(detail -> assertThat(detail).contains("ImportApiOutsideImporter", "CaseImportService"))
                .anySatisfy(
                        detail -> assertThat(detail).contains("ImportApiOutsideImporter", "ImportedHistoryService"));
    }
}
