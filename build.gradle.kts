// ============================================================================
// ルートプロジェクトの build.gradle.kts
//
// ここには実際のビルドロジックは書かない(集約のみ)。
// 各サブプロジェクトは build-logic (複合ビルド) が提供する precompiled script
// plugin (template.kotlin-common / template.kotlin-pure / template.spring-library
// / template.spring-application) を個別に適用する。
// ============================================================================
