// ============================================================================
// :batch
//
// バッチ処理モジュール。Spring Batch でジョブ/ステップを定義する。
//
// - spring-boot-starter-batch: Spring Batch 6 本体 (JobRepository, JobOperator,
//   ChunkOrientedStepBuilder 等)。Spring Boot の BOM がバージョンを管理する。
// - spring-boot-starter-jdbc: JobRepository のメタデータテーブル (BATCH_JOB_INSTANCE 等) は
//   Spring Batch 6 でも JDBC 前提であり、settlements/errors テーブルへの書き込みも
//   この :batch モジュール内では JdbcTemplate で行う (:adapter-persistence の R2DBC とは
//   独立した経路。Spring Batch 自体が R2DBC をサポートしていないため)。
// - org.postgresql:postgresql: 本番の DataSource (:bootstrap が組み立てる) 及び
//   テスト用 PostgreSQL Testcontainer 接続の両方で使う JDBC ドライバ。
//
// 注意: このモジュールは :adapter-messaging に依存しない (settings.gradle.kts が
// 定義するモジュールグラフ上、:batch -> :adapter-messaging という辺は存在しない)。
// SettlementFilePort の実装 (S3 読み込み) は :adapter-messaging が提供するが、
// :batch はポート (:application) にしか依存せず、実装 Bean の注入は :bootstrap の
// Spring コンテキストで解決される (:bootstrap は :adapter-messaging と :batch の両方に依存する)。
// ============================================================================

plugins {
    id("template.spring-library")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))

    implementation("org.springframework.boot:spring-boot-starter-batch")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.postgresql:postgresql")

    testImplementation(testFixtures(project(":domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.batch:spring-batch-test")
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.junit.jupiter)
}
