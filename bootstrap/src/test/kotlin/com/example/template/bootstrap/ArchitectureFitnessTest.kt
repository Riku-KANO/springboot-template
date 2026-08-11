package com.example.template.bootstrap

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import org.junit.jupiter.api.Test

/** 依存方向をレビュー時の合意ではなく、継続的に失敗するfitness functionとして固定する。 */
class ArchitectureFitnessTest {
    private val productionClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.template")

    @Test
    fun `domain and application do not depend on outer layers`() {
        noClasses()
            .that()
            .resideInAPackage("..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..application..", "..adapter..", "..batch..", "..bootstrap..")
            .check(productionClasses)

        noClasses()
            .that()
            .resideInAPackage("..application..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..adapter..", "..batch..", "..bootstrap..", "org.springframework..")
            .check(productionClasses)
    }

    @Test
    fun `adapters and batch do not reach into the composition root`() {
        noClasses()
            .that()
            .resideInAnyPackage("..adapter..", "..batch..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..bootstrap..")
            .check(productionClasses)
    }

    @Test
    fun `top level architectural slices are free of cycles`() {
        slices()
            .matching("com.example.template.(*)..")
            .should()
            .beFreeOfCycles()
            .check(productionClasses)
    }
}
