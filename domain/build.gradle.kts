// ============================================================================
// :domain
//
// ドメインモデル(集約・値オブジェクト・ドメインサービス・リポジトリインターフェース)
// を置くモジュール。Arrow のみに依存し、Spring には一切依存しない。
//
// java-test-fixtures を適用しておくことで、後続チャンクでドメインの
// テストデータジェネレータや Either 用アサーションを他モジュールへ共有できる。
// ============================================================================

plugins {
    id("template.kotlin-pure")
    `java-test-fixtures`
}

// template.kotlin-common / template.kotlin-pure は testImplementation にしか
// kotest-property を配線していない。testFixtures ソースセットは別扱いなので、
// Arb ジェネレータをここ (と、これを利用する後続モジュール) で使うために
// 明示的に追加する。testFixturesApi にしているのは、Arbs.kt が公開する
// `Arb<...>` の型が他モジュールのテストコードからも直接見える必要があるため。
dependencies {
    testFixturesApi(libs.kotest.property)
}
