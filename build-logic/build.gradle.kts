// ============================================================================
// build-logic 自体のビルド定義
//
// kotlin-dsl プラグインを適用し、precompiled script plugin
// (src/main/kotlin/template.*.gradle.kts) が `plugins { id(...) }` で
// 適用する各プラグイン本体を implementation 依存として宣言する。
//
// 静的解析について: 当初 detekt 1.23.8 も導入を試みたが、
// 「detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21.
//  This is not supported.」というハードエラーが spring-library 系の全モジュールで
// 発生し、設定変更では回避できなかったため不採用とした (detekt がバンドルする
// Kotlin コンパイラフロントエンドが本プロジェクトの Kotlin 2.3.21 と非互換)。
// そのため detekt-gradle-plugin は依存に含めていない。ktlint + spotless のみで
// 静的解析を運用する (バージョン自体は gradle/libs.versions.toml に記録済み)。
// ============================================================================

plugins {
    `kotlin-dsl`
}

repositories {
    gradlePluginPortal()
    mavenCentral()
}

dependencies {
    implementation(libs.kotlin.gradle.plugin)
    implementation(libs.kotlin.allopen)
    implementation(libs.spring.boot.gradle.plugin)
    implementation(libs.spring.dependency.management.plugin)
    implementation(libs.ksp.gradle.plugin)
    implementation(libs.ktlint.gradle.plugin)
    implementation(libs.spotless.gradle.plugin)
}
