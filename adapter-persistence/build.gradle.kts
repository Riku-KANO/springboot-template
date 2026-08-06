// ============================================================================
// :adapter-persistence
//
// 永続化アダプタ。R2DBC / Flyway など Spring 系の永続化技術を利用する。
//
// - spring-boot-starter-data-r2dbc + r2dbc-postgresql: アプリの実行時 (R2DBC) 経路。
// - org.postgresql:postgresql (JDBC ドライバ): Flyway 自体が JDBC 経由でしか
//   マイグレーションを実行できないため、また Spring Batch 6 の JobRepository も
//   JDBC 前提であるため、R2DBC とは別に必要になる (:bootstrap 側で DataSource も
//   composition root として組み立てる必要がある点に注意。詳細は README 相当の
//   報告をチャット越しに渡す)。
// - flyway-core + flyway-database-postgresql: スキーマは Flyway が一元管理し、
//   spring.batch.jdbc.initialize-schema=never を前提にする (:bootstrap 側の設定)。
// ============================================================================

plugins {
    id("template.spring-library")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))

    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")
    implementation(libs.r2dbc.postgresql)
    implementation("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")

    // spring-tx / spring-r2dbc の Kotlin coroutine 拡張 (executeAndAwait 等) はコンパイル時にしか
    // kotlinx-coroutines-reactor / -reactive を要求せず、実行時依存としては伝播してこない
    // (Spring 側が「Kotlin コルーチンを使うかどうか」を各利用者に委ねているため)。
    // そのため R2dbcTxRunner がこれらの拡張関数を呼ぶ以上、ここで明示的に追加する必要がある。
    // バージョンは明示せず、Spring Boot BOM が import する kotlinx-coroutines-bom の管理下に置く。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")

    testImplementation(testFixtures(project(":domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}
