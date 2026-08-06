package com.example.template.adapter.web.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.Customizer.withDefaults
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.web.server.SecurityWebFilterChain

/**
 * JWT リソースサーバーとしてのセキュリティ設定。
 *
 * ##### なぜここだけ `@Configuration` + `@Bean` という「非関数型」なのか
 * このテンプレート全体としてはコンストラクタインジェクションを主体とした明示的な
 * Bean 定義を好むが、Spring Security の `ServerHttpSecurity` DSL 自体が
 * 「フレームワークから渡されるビルダーオブジェクトを段階的に組み立てる」形を前提に
 * 設計されている。加えて `ReactiveSecurityAutoConfiguration` などの自動設定は
 * `@ConditionalOnMissingBean(SecurityWebFilterChain::class)` でユーザー定義の
 * Bean の有無を判定する。この2点から、ここで無理に他の関数型な設計に寄せても
 * 複雑さが増えるだけで得るものがないため、フレームワークの流儀に素直に従う
 * (「関数型に固執しない」というテンプレートの立場を明示するための実例でもある)。
 *
 * ##### issuer / JWK URI をプロパティ駆動にする
 * `.oauth2ResourceServer { it.jwt(withDefaults()) }` はこのモジュール内で
 * `ReactiveJwtDecoder` を自前で組み立てない。Spring Boot の自動設定
 * (`spring.security.oauth2.resourceserver.jwt.issuer-uri` あるいは `jwk-set-uri`
 * プロパティ) にその生成を委ねている。実際の IdP のエンドポイントをこのモジュールに
 * ハードコードしないことで、プロファイル (local/dev/prod 等) ごとに異なる IdP を
 * 挿し替え可能にする。:bootstrap 側でこのいずれかのプロパティを設定する必要があり、
 * 設定が完全に欠けている場合はこの [securityWebFilterChain] Bean 自体の生成が
 * (デコーダを解決できず) 失敗する点に注意。`jwk-set-uri` を使う場合、
 * `NimbusReactiveJwtDecoder.withJwkSetUri(...)` は Bean 生成時点では JWKS を
 * フェッチしない (実際のトークン検証時に遅延フェッチする) ため、起動時に IdP への
 * 疎通が無くてもアプリケーションは起動できる。
 */
@Configuration
@EnableWebFluxSecurity
class SecurityConfig {
    @Bean
    fun securityWebFilterChain(http: ServerHttpSecurity): SecurityWebFilterChain =
        http
            // JWT ベアラートークンによるステートレス API のため、Cookie セッションを
            // 前提にした CSRF 対策は不要 (むしろ有効なままだと POST/PUT 等が弾かれる)。
            .csrf { it.disable() }
            .authorizeExchange { exchange ->
                exchange
                    .pathMatchers("/actuator/health/**", "/actuator/info")
                    .permitAll()
                    .anyExchange()
                    .authenticated()
            }.oauth2ResourceServer { it.jwt(withDefaults()) }
            .build()
}
