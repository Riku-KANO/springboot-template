// ============================================================================
// template.kotlin-pure
//
// Arrow のみを依存に持つ「純粋な」Kotlin モジュール向け convention plugin。
// :domain / :application が適用する。Spring は絶対に持ち込まない。
//
// - template.kotlin-common を継承
// - Arrow BOM (arrow-stack) + arrow-core / arrow-fx-coroutines / arrow-optics
// - KSP + arrow-optics-ksp-plugin (@optics アノテーションのコード生成用)
// ============================================================================

import org.gradle.api.artifacts.VersionCatalogsExtension

plugins {
    id("template.kotlin-common")
    id("com.google.devtools.ksp")
}

// precompiled script plugin の中では型安全な `libs.xxx` アクセサが生成されないため、
// VersionCatalogsExtension を介して明示的にカタログを取得する。
val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")

dependencies {
    // Arrow は implementation ではなく api で公開する。
    // :domain / :application のポートやドメイン関数は Either / NonEmptyList を
    // シグネチャに含んでおり、Arrow は「実装の詳細」ではなく「公開 ABI の一部」。
    // implementation にすると :adapter-web などの下流モジュールが Either を
    // 解決できずコンパイルエラーになる。
    api(platform(libs.findLibrary("arrow-bom").get()))
    api(libs.findLibrary("arrow-core").get())
    api(libs.findLibrary("arrow-fx-coroutines").get())
    api(libs.findLibrary("arrow-optics").get())

    ksp(libs.findLibrary("arrow-optics-ksp-plugin").get())
}
