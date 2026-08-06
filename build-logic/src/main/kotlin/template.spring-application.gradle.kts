// ============================================================================
// template.spring-application
//
// 実行可能アプリケーションを作る convention plugin。:bootstrap のみが適用する。
//
// - template.spring-library を継承 (Spring Boot BOM 管理はそのまま利用)
// - bootJar / bootBuildImage を有効化し、通常の jar は無効化する
//   (@SpringBootApplication のエントリポイントはこのモジュールにのみ存在する)
// ============================================================================

import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("template.spring-library")
}

tasks.named<BootJar>("bootJar") {
    enabled = true
}

tasks.named<Jar>("jar") {
    enabled = false
}
