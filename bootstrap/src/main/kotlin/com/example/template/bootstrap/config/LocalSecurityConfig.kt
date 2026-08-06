package com.example.template.bootstrap.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * ローカル開発専用の permitAll セキュリティチェイン。
 *
 * ##### なぜ adapter-web の SecurityConfig を書き換えずに済ませられるか
 * :adapter-web の `SecurityConfig` (JWT リソースサーバーとしての認可) は本チャンクの守備範囲外
 * (bootstrap 配下と docker 配下のみが本チャンクの担当) であり、そもそも書き換えるべきでもない —
 * dev/stg/prod では本物の JWT 認可がそのまま必要だからだ。一方 WebFlux の Spring Security は
 * `SecurityWebFilterChain` を複数 Bean 登録でき、`@Order` の順に並べて「最初にマッチしたチェインだけが
 * 適用される」("最初に勝ったチェインの外では他のチェインは一切評価されない")。
 * `ServerHttpSecurity.build()` はデフォルトで `securityMatcher` を明示しない限り「任意のリクエストに
 * マッチする」チェインになるため、ここで `@Order(Ordered.HIGHEST_PRECEDENCE)` の permitAll チェインを
 * 追加登録するだけで、adapter-web 側の (`@Order` 未指定 = 最低優先度の) `SecurityConfig.securityWebFilterChain`
 * より必ず先に評価され、実質的に adapter-web の JWT 認可を上書きできる。
 *
 * ##### なぜ jwk-set-uri 自体は local でも消せないのか
 * このチェインが有効でも、adapter-web の `SecurityConfig` 自身の Bean 生成は (使われるかどうかに
 * 関わらず) コンテキスト起動時に必ず走る。その `.oauth2ResourceServer { it.jwt(withDefaults()) }` は
 * `ReactiveJwtDecoder` Bean の解決を必要とするため、`spring.security.oauth2.resourceserver.jwt.issuer-uri`
 * か `jwk-set-uri` のどちらかを設定しておかないと、adapter-web の Bean 生成自体が失敗しアプリが
 * 起動できない (このテンプレートの最も踏み抜きやすい落とし穴、という Chunk 6 依頼者からの警告どおり)。
 * `NimbusReactiveJwtDecoder.withJwkSetUri(...)` は Bean 生成時点では JWKS をフェッチしない
 * (実際にトークンを検証する際に遅延フェッチする) ため、到達不能なダミー URI
 * (application-local.yml の `jwk-set-uri`) を設定するだけでよく、実際の IdP への疎通は一切要らない
 * (adapter-web/SecurityConfigTest.kt が既にこの挙動を実証済み)。
 *
 * `template.security.permit-all=true` が設定されているときだけ有効 ([TemplateSecurityProperties]
 * 参照)。application-local.yml だけがこのプロパティを true にするため、dev/stg/prod では
 * この Bean 自体が生成されず、adapter-web の本来の JWT 認可がそのまま効く。
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties(TemplateSecurityProperties::class)
@ConditionalOnProperty(prefix = "template.security", name = ["permit-all"], havingValue = "true")
class LocalSecurityConfig {
    @Bean
    @Order(Ordered.HIGHEST_PRECEDENCE)
    fun localPermitAllSecurityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            .csrf { it.disable() }
            .authorizeExchange { it.anyExchange().permitAll() }
            .build()
}
