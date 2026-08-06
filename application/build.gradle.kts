// ============================================================================
// :application
//
// ユースケース(アプリケーションサービス)を置くモジュール。:domain にのみ依存し、
// Spring には一切依存しない。
// ============================================================================

plugins {
    id("template.kotlin-pure")
}

dependencies {
    // Order/Settlement 等のドメイン型を公開シグネチャ (ポート/ユースケースの引数・戻り値) に
    // 含めるため、implementation ではなく api で公開する。:adapter-* が :application 経由で
    // Order 等を参照する際に、:domain への依存が自動的に伝播しないと解決できない。
    api(project(":domain"))

    // :domain の testFixtures (Arb ジェネレータ・Either アサーション) をユースケースのテストで再利用する。
    testImplementation(testFixtures(project(":domain")))
}
