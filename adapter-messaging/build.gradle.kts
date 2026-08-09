// ============================================================================
// :adapter-messaging
//
// メッセージングアダプタ。Spring Cloud AWS (SQS/S3) + AWS SDK v2 (Step Functions) を利用する。
//
// - spring-cloud-aws-starter / -starter-sqs / -starter-s3: SqsTemplate, @SqsListener,
//   S3Template 等の Spring 統合を提供する。Spring Cloud AWS の BOM が管理する範囲。
// - software.amazon.awssdk:sfn: Step Functions は Spring Cloud AWS のカバー範囲外
//   (SQS/S3/SNS/DynamoDB 等はラップされているが SFN 用の starter は存在しない) ため、
//   AWS SDK v2 の生クライアント (SfnAsyncClient) を直接使う。aws-sdk-bom がバージョンを管理する。
// - io.arrow-kt:arrow-resilience: カタログ (gradle/libs.versions.toml) にエイリアスが無いため、
//   座標を直書きする。バージョンは :application 経由で推移的に持ち込まれる arrow-bom
//   (template.kotlin-pure が `api(platform(...))` している) がここでも解決に使われるが、
//   このモジュール自身が直接 arrow-resilience を要求するという事実を明示するため
//   platform import 自体もここで重ねて宣言している。
//   (Chunk 5 からの報告: gradle/libs.versions.toml に arrow-resilience のカタログエイリアスを
//    追加した方が将来のバージョン一元管理としては望ましいが、本チャンクの守備範囲外のファイルのため
//    直書きに留めた。)
// - tools.jackson.module:jackson-module-kotlin (カタログエイリアス既存): TaskCallbackPort.notifySuccess
//   の汎用ペイロード (SettlementReport 等) を Step Functions の SendTaskSuccess output (JSON 文字列)
//   にシリアライズするための ObjectMapper (Jackson 3) を得る。Spring Boot の
//   JacksonAutoConfiguration がこれを検知して Kotlin モジュール/JavaTimeModule 込みで自動構成する。
// - org.jetbrains.kotlinx:kotlinx-coroutines-jdk8: AWS SDK v2 の CompletableFuture を
//   `.await()` で coroutine に橋渡しするための拡張関数のみに必要な最小依存。
//   kotlinx-coroutines-core 自体は :application 経由 (arrow-fx-coroutines の推移的依存) で
//   既に解決されているため、jdk8 統合モジュールだけを追加すればよい。
// - org.springframework.batch:spring-batch-core: SettlementTaskListener (SQS リスナー) が
//   :batch モジュールの settlementReconciliationJob を起動するために Job / JobOperator /
//   JobParametersBuilder の「型」を参照する必要がある。:batch -> :adapter-messaging の逆はもちろん、
//   :adapter-messaging -> :batch という project 依存も settings.gradle.kts のモジュールグラフには
//   存在せず追加できない (本チャンクの守備範囲外)。:application に新しいポート
//   (例: SettlementJobLauncherPort) を追加すれば project 依存なしで解決できるが、それも
//   :application を変更することになり守備範囲外。そのため Spring Batch という「共通のサードパーティ
//   ライブラリ」だけを両モジュールがそれぞれ独立に依存し、実際の Bean (Job インスタンス) の解決は
//   :bootstrap が組み立てる単一の ApplicationContext に委ねる、という設計にした
//   (SettlementFilePort を :adapter-messaging が実装し :batch が消費するのと対称的な構造)。
//   starter 全体ではなく spring-batch-core だけを使うのは、autoconfiguration や
//   starter-jdbc 等の余計な依存をこのモジュールに持ち込みたくないため。
// ============================================================================

plugins {
    id("template.spring-library")
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":application"))
    implementation("org.springframework.boot:spring-boot-starter-validation")

    implementation(platform(libs.spring.cloud.aws.bom))
    implementation(platform(libs.aws.sdk.bom))
    implementation("io.awspring.cloud:spring-cloud-aws-starter")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-sqs")
    implementation("io.awspring.cloud:spring-cloud-aws-starter-s3")
    implementation("software.amazon.awssdk:sfn")

    implementation(platform(libs.arrow.bom))
    implementation("io.arrow-kt:arrow-resilience")

    implementation(libs.jackson.module.kotlin)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8")
    implementation("org.springframework.batch:spring-batch-core")

    testImplementation(testFixtures(project(":domain")))
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(libs.testcontainers.localstack)
    testImplementation(libs.testcontainers.junit.jupiter)
}
