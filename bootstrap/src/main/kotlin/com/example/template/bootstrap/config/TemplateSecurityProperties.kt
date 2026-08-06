package com.example.template.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * :bootstrap 自身が持つ、composition root 固有の設定値。
 *
 * `permitAll` を true にすると [LocalSecurityConfig] が最優先の permitAll チェインを追加登録し、
 * adapter-web の JWT リソースサーバー認可を実質的にバイパスする。ローカル開発のたびに
 * 本物の JWT を用意する手間を省くためのスイッチであり、application-local.yml だけで true にする
 * (dev/stg/prod では絶対に設定しないこと。詳細は [LocalSecurityConfig] の KDoc を参照)。
 *
 * ##### `@ConstructorBinding` は必要か (実機で確認した結果)
 * Spring Boot 2.x 時代は `@ConfigurationProperties` をコンストラクタ束縛させるために
 * `@ConstructorBinding` の明示が必須だったが、Boot 3.0 以降は「対象クラスのコンストラクタが
 * 1つだけならそれを自動的に束縛用コンストラクタとみなす」ため付与不要になった、というのが
 * ドキュメント上の理解である。このクラスは実際に `@ConstructorBinding` を付けずに実装し、
 * アプリを起動して `template.security.permit-all=true` (application-local.yml) が実際に
 * [LocalSecurityConfig] のログ/実際の permitAll 挙動として反映されることを確認した
 * (本チャンクの Definition of Done の smoke test 3〜5 は、この Bean が正しく無効化された
 * JWT 認可の状態、つまりこの束縛が成功している状態でしか成立しない)。よってこのプロジェクトの
 * Spring Boot 4.1.0 + Kotlin 2.3.21 の組み合わせでは `@ConstructorBinding` は不要、というのが
 * 実証済みの結論。
 */
@ConfigurationProperties(prefix = "template.security")
data class TemplateSecurityProperties(
    val permitAll: Boolean = false,
)
