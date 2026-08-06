package com.example.template.bootstrap

import com.example.template.bootstrap.config.paymentGatewayBeans
import com.example.template.bootstrap.config.persistenceBeans
import com.example.template.bootstrap.config.useCaseBeans
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * このテンプレートの composition root (アプリケーション全体を組み立てる唯一の起点)。
 *
 * ##### なぜ scanBasePackages を明示するのか
 * `@SpringBootApplication` のデフォルトのコンポーネントスキャン範囲は「このクラスが置かれた
 * パッケージとそのサブパッケージ」だけである。このクラスは `com.example.template.bootstrap` に
 * あるため、デフォルトのままでは `com.example.template.adapter.*` や `com.example.template.batch`
 * 配下の `@Component` / `@Configuration` (OrderController, SecurityConfig, S3SettlementFileAdapter,
 * SettlementReconciliationJobConfig 等、Chunk 3〜5 が実装した Spring 対応クラス群) が一切
 * スキャンされず、実行時に `NoSuchBeanDefinitionException` の連鎖になる。`scanBasePackages` を
 * `"com.example.template"` (全モジュール共通の親パッケージ) に広げることで、全モジュールの
 * Spring コンポーネントを一括で拾う。
 *
 * ##### 関数型 Bean 定義 (beans{}) と @Configuration の使い分け (このテンプレートの立場)
 * このテンプレートは「関数型プログラミングに教条的にこだわらない」ことを明示する立場を取る。
 * :application (ユースケース) や :adapter-persistence のポート実装のように、意図的に Spring
 * フリー・非アノテーションで書かれたプレーンな Kotlin クラスは、[config] パッケージの関数型
 * `beans { }` DSL (`org.springframework.context.support.beans`) で明示的にコンストラクタ注入する
 * ([useCaseBeans], [persistenceBeans], [paymentGatewayBeans])。一方、Spring Security の
 * `ServerHttpSecurity` DSL、デュアル `DataSource`/`ConnectionFactory` 構成、AWS SDK クライアントの
 * 組み立てのように「フレームワークの条件付きオートコンフィグレーションと素直に噛み合わせた方が
 * 得」な箇所 (`config.R2dbcTransactionConfig`, `config.AwsClientsConfig`, `config.LocalSecurityConfig`)
 * では普通の `@Configuration` + `@Bean` を使う。どちらか一方の流儀に統一することにはこだわらず、
 * フレームワークと戦うコストがメリットを上回る場所では素直にフレームワークの流儀に従う、
 * というのがこのテンプレート全体を通じた設計判断である。
 */
@SpringBootApplication(scanBasePackages = ["com.example.template"])
class TemplateApplication

fun main(args: Array<String>) {
    runApplication<TemplateApplication>(*args) {
        // :application / :adapter-persistence / (ResilientPaymentGatewayAdapter を含む)
        // :adapter-messaging 由来の、意図的に @Component を持たないプレーンな Kotlin クラス群を
        // ApplicationContextInitializer として登録する (TemplateApplication の KDoc を参照)。
        // `beans { }` が返す BeanDefinitionDsl は ApplicationContextInitializer<GenericApplicationContext>
        // を実装しており、この addInitializers(...) にそのまま渡せる。
        addInitializers(useCaseBeans(), persistenceBeans(), paymentGatewayBeans())
    }
}
