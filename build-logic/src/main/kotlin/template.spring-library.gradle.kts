// ============================================================================
// template.spring-library
//
// Spring (WebFlux, R2DBC, Batch, Spring Cloud AWS 等) を利用する
// "ライブラリ" モジュール向け convention plugin。
// :adapter-persistence / :adapter-web / :adapter-messaging / :batch が適用する。
//
// - template.kotlin-common を継承
// - org.springframework.boot + io.spring.dependency-management を適用し、
//   Spring Boot BOM (spring-boot-dependencies) にバージョン管理を委ねる
//   (org.springframework.boot と io.spring.dependency-management を併用すると
//   Boot プラグインが自動的に自身の BOM を dependency-management にインポートする)
// - kotlin("plugin.spring") (open クラス化などの Spring 向け Kotlin コンパイラプラグイン)
// - ライブラリモジュールなので bootJar は作らない。代わりに通常の jar を有効化する。
// ============================================================================

import org.springframework.boot.gradle.tasks.bundling.BootJar

plugins {
    id("template.kotlin-common")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    kotlin("plugin.spring")
}

tasks.named<BootJar>("bootJar") {
    enabled = false
}

tasks.named<Jar>("jar") {
    enabled = true
    // bootJar が存在しないためクラス識別用の "plain" 分類子は不要 (Boot プラグイン適用時の既定動作を上書き)
    archiveClassifier.set("")
}
