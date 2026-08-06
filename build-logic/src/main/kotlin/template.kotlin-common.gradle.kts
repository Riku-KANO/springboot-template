// ============================================================================
// template.kotlin-common
//
// 全モジュール共通の基盤設定。
// - Kotlin/JVM プラグイン、Java 17 ツールチェーン
// - -Xjsr305=strict (Java の @Nullable/@NonNull を厳格に解釈させる)
// - JUnit Platform を使ったテストタスク
// - ktlint / spotless による静的解析
// - 共通テスト依存 (junit-jupiter, mockk, kotest-property, kotlinx-coroutines-test)
//
// 注意: このプラグインは Spring に一切依存しない。:domain / :application は
// これ (もしくは template.kotlin-pure) だけを適用することで Spring フリーを保つ。
// テスト用の junit-jupiter / kotlinx-coroutines-test も Spring Boot BOM では
// なく JUnit BOM + 明示バージョンで解決しているのはそのため。
// ============================================================================

import org.gradle.api.artifacts.VersionCatalogsExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.File

plugins {
    kotlin("jvm")
    // api コンフィギュレーションを使うために必要。:domain / :application は
    // Arrow の Either を公開シグネチャに含むため、api で下流へ伝播させる。
    `java-library`
    id("org.jlleitschuh.gradle.ktlint")
    id("com.diffplug.spotless")
}

// precompiled script plugin (このファイル自体) の中では型安全な `libs.xxx` アクセサが
// 生成されないため、VersionCatalogsExtension を介して明示的にカタログを取得する。
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

ktlint {
    // KSP (arrow-optics-ksp-plugin 等) が生成するコードは1プロパティ1行にすべて
    // 詰め込まれており、140文字の max_line_length を機械的に超える。生成コードは
    // 人間が編集しないため、ktlint の対象から除外する (手書きコードの品質チェックという
    // 目的からしても、生成物を対象に含めることに意味がない)。
    filter {
        // FileTreeElement#getPath() は各ソースディレクトリを起点とした相対パスであり、
        // KSP の出力ディレクトリ自体が起点になる場合 "generated" を含まない。
        // そのため絶対パス (FileTreeElement#getFile()) で判定する。
        exclude { entry -> entry.file.path.replace(File.separatorChar, '/').contains("/generated/") }
    }
}

spotless {
    // 改行コードは常に LF に固定する (Windows のデフォルト挙動 = native = CRLF に
    // フォールバックすると、リポジトリ上の LF ファイルと食い違って spotlessCheck が
    // 落ちるため)。.editorconfig の end_of_line = lf とも一致させている。
    lineEndings = com.diffplug.spotless.LineEnding.UNIX

    // Kotlin 本体のソース (src/**/*.kt) は org.jlleitschuh.gradle.ktlint 側で
    // lint する。spotless はビルドスクリプト (*.gradle.kts) のフォーマットを担当し、
    // 役割を分けて二重チェック・競合を避ける。
    kotlinGradle {
        target("*.gradle.kts")
        ktlint()
    }
}

dependencies {
    testImplementation(platform(libs.findLibrary("junit-bom").get()))
    testImplementation(libs.findLibrary("junit-jupiter").get())
    testRuntimeOnly(libs.findLibrary("junit-platform-launcher").get())

    testImplementation(libs.findLibrary("mockk").get())
    testImplementation(libs.findLibrary("kotest-property").get())
    testImplementation(libs.findLibrary("kotlinx-coroutines-test").get())
}
