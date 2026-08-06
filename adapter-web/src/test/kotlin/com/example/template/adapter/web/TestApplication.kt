package com.example.template.adapter.web

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * `:adapter-web` は "アダプタ" モジュールであり `@SpringBootApplication` は
 * `:bootstrap` にしか存在しない (adapter-web/build.gradle.kts のコメント参照)。
 * `@WebFluxTest` 等の Spring Boot テストスライスはコンテキストのルートとなる
 * `@SpringBootConfiguration` をクラスパスから探すため、このモジュール単体で
 * スライステストを動かすためだけの最小限の起動クラスをテストソースに用意する。
 * 本番のクラスパスには含まれない (src/test 配下のため jar に混入しない)。
 */
@SpringBootApplication
class TestApplication
