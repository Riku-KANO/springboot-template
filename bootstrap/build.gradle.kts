// ============================================================================
// :bootstrap
//
// アプリケーションの起動モジュール。@SpringBootApplication はここにのみ存在し、
// 他の全モジュールに依存する唯一のモジュールである。bootJar を生成するのも
// このモジュールだけ。
//
// 各 adapter モジュールは Spring 関連のライブラリを `implementation` (api ではない) で
// 宣言しているため (adapter-persistence/build.gradle.kts 等のコメント参照)、それらの型
// (DatabaseClient, TransactionalOperator, ServerHttpSecurity, SfnAsyncClient 等) は
// ランタイムクラスパスには推移的に伝播するが、コンパイル時には :bootstrap から見えない。
// composition root である :bootstrap は「どの実装をどのポートに割り当てるか」を
// 自分で書く (config/*Beans.kt, config/*Config.kt) 必要があるため、それらの型を
// 直接参照できるよう、ここで必要な starter / SDK を明示的に (implementation として)
// 再宣言する。オートコンフィグレーション自体 (DataSourceAutoConfiguration 等) は
// ランタイムクラスパスにさえ乗っていれば発火するので、この再宣言は「コンパイル時の
// 型解決のため」であって、機能を新たに有効化するためではない点に注意。
// ============================================================================

plugins {
    id("template.spring-application")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation(project(":adapter-persistence"))
    implementation(project(":adapter-web"))
    implementation(project(":adapter-messaging"))
    implementation(project(":batch"))

    // @SpringBootApplication / runApplication のために必要な最小限の starter。
    // 実際に使う機能別 starter (webflux, r2dbc 等) は各 adapter 側で追加し、
    // ここでは推移的依存として取り込まれる想定。
    implementation("org.springframework.boot:spring-boot-starter")

    // actuator: どの adapter モジュールも宣言していないため、/actuator/health 等の
    // Definition of Done で要求されるエンドポイントのためにここで初めて追加する。
    implementation("org.springframework.boot:spring-boot-starter-actuator")

    // R2DBC: DatabaseClient / ReactiveTransactionManager / TransactionalOperator (spring-tx) の
    // 型を config/PersistenceBeans.kt, config/R2dbcTransactionConfig.kt から直接参照するために必要。
    // ConnectionFactory 自体の組み立ては R2dbcAutoConfiguration に委ねる (Bean 定義は書かない)。
    implementation("org.springframework.boot:spring-boot-starter-data-r2dbc")

    // JDBC: DataSourceProperties / DataSourceBuilder (config/JdbcDataSourceConfig.kt) の型を
    // 直接参照するために必要。実機で判明した重要な事実として、Boot の
    // DataSourceAutoConfiguration は @ConditionalOnMissingBean(ConnectionFactory::class) を持ち、
    // R2DBC の ConnectionFactory Bean が存在すると JDBC 用 DataSource を一切自動構成しない
    // (JdbcDataSourceConfig.kt のクラス KDoc に詳細と実機での確認手順を記載)。そのため
    // JDBC 用 DataSource だけはここで明示的に組み立てる必要があり、その型を得るために追加する。
    implementation("org.springframework.boot:spring-boot-starter-jdbc")

    // Spring Security (reactive): config/LocalSecurityConfig.kt が ServerHttpSecurity /
    // SecurityWebFilterChain を直接組み立てるために必要 (adapter-web の SecurityConfig を
    // 書き換えずに、ローカル専用の permitAll チェインを追加登録するため)。
    implementation("org.springframework.boot:spring-boot-starter-security")

    // WebFlux + coroutine: logging/RequestLoggingWebFilter.kt (CoWebFilter/ServerWebExchange) と、
    // それが使う MDCContext (requestId の MDC 伝播を確認するための最小限のログ出力 Filter。
    // adapter-web の RequestIdWebFilter を書き換えずに済ませるための composition root 側の追加) に必要。
    implementation("org.springframework.boot:spring-boot-starter-webflux")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-slf4j")

    // Spring Batch: JobLauncherApplicationRunner (Boot 標準) がジョブ起動を担うため
    // composition root 側にジョブ起動用のコードは無いが、application-batch.yml が有効化する
    // spring.batch.job.* プロパティの意味を検証するためにビルド時に spring-boot-starter-batch の
    // 自動構成が発火することを保証する必要があり、明示的に依存させている
    // (実機検証の結果、素の `--spring.batch.job.name=... settlementDate=...` という
    // 非オプション引数だけで正しく動くことを確認済み。詳細はチャット越しの報告を参照)。
    implementation("org.springframework.boot:spring-boot-starter-batch")

    // Spring Cloud AWS: config/AwsClientsConfig.kt が AwsClientBuilderConfigurer
    // (region/credentials/spring.cloud.aws.endpoint の解決を担う Spring Cloud AWS 自身の
    // ヘルパー) を使って SfnAsyncClient を組み立てるために必要。
    implementation(platform(libs.spring.cloud.aws.bom))
    implementation("io.awspring.cloud:spring-cloud-aws-starter")

    // AWS SDK v2: Step Functions 用の Spring Cloud AWS starter は存在しない
    // (adapter-messaging/build.gradle.kts のコメント参照) ため、SfnAsyncClient の型を
    // 直接参照して Bean を組み立てる。
    implementation(platform(libs.aws.sdk.bom))
    implementation("software.amazon.awssdk:sfn")

    // Flyway オートコンフィグレーション: これも実機で踏んだ地雷。org.flywaydb:flyway-core
    // (adapter-persistence が implementation で持ち込む) をクラスパスに置くだけでは
    // FlywayAutoConfiguration が一切発火しない (--debug の条件評価レポートにすら
    // 出現しないことを実機で確認した)。Spring Boot 4 で自動構成が機能別モジュールに
    // 分割された際、Flyway 統合は spring-boot-autoconfigure 本体にも spring-boot-jdbc にも
    // 含まれず、独立した org.springframework.boot:spring-boot-flyway という新しい
    // 座標のモジュールに切り出されており、これを明示的に依存追加しない限り
    // FlywayAutoConfiguration 自体がクラスパス上に存在しない。
    implementation("org.springframework.boot:spring-boot-flyway")
}
