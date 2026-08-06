// ============================================================================
// :adapter-web
//
// Web アダプタ。WebFlux / Security / springdoc など Spring 系の Web 技術を利用する。
//
// - spring-boot-starter-webflux: Reactor Netty 上で動く WebFlux。suspend 関数を
//   ハンドラとして直接使えるのがこのスタックを選んだ理由そのもの。
// - spring-boot-starter-validation: リクエスト DTO の Bean Validation (@NotBlank 等) 用。
//   ただしヘッドライン機能である「累積バリデーション」自体は :domain / :application の
//   Either ベースの検証が担い、Bean Validation は「JSON として壊れた形すら成していない」
//   レベルの防御にとどめる (詳細は order/dto 配下のコメントを参照)。
// - spring-boot-starter-security + spring-boot-starter-oauth2-resource-server: JWT
//   リソースサーバーとしての認可。
// - kotlinx-coroutines-reactor: WebFlux (Reactor Mono/Flux) と Kotlin coroutine の相互運用。
// - kotlinx-coroutines-slf4j: MDCContext (filter/RequestIdWebFilter.kt 参照) のために必要。
// - springdoc-openapi-starter-webflux-ui: OpenAPI ドキュメント自動生成。
// ============================================================================

plugins {
    id("template.spring-library")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))

    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactor")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")
    implementation(libs.springdoc.openapi.starter.webflux.ui)

    testImplementation(testFixtures(project(":domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    // Spring Boot 4 ではテストスライス関連クラス (@WebFluxTest 等) がモジュール分割され、
    // spring-boot-starter-test だけでは付いてこない。@WebFluxTest 自体は
    // spring-boot-webflux-test に、AutoConfigureWebTestClient 等はこのスターター経由で解決する。
    testImplementation("org.springframework.boot:spring-boot-starter-webflux-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("io.projectreactor:reactor-test")
}
