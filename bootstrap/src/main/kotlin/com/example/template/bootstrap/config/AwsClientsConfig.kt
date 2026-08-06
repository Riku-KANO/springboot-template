package com.example.template.bootstrap.config

import io.awspring.cloud.autoconfigure.core.AwsClientBuilderConfigurer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.services.sfn.SfnAsyncClient

/**
 * AWS SDK v2 の生クライアントのうち、Spring Cloud AWS がオートコンフィグしないものを組み立てる。
 *
 * Spring Cloud AWS は S3/SQS/SNS/DynamoDB 等の主要サービスは専用の starter (spring-cloud-aws-starter-s3
 * 等) でオートコンフィグするが、Step Functions 用の starter は提供していない
 * (:adapter-messaging/build.gradle.kts のコメント参照)。そのため `SfnAsyncClient` だけは
 * composition root がここで直接組み立てる必要がある。
 *
 * 手組みするといっても、region/credentials/エンドポイント上書きのロジックまで自前で書く必要はない。
 * `AwsClientBuilderConfigurer` は Spring Cloud AWS 自身が `S3AutoConfiguration` /
 * `SqsAutoConfiguration` 等の内部で使っているのと全く同じヘルパーであり、
 * `spring.cloud.aws.region.static` / `spring.cloud.aws.credentials.*` / `spring.cloud.aws.endpoint`
 * (LocalStack へのグローバルなエンドポイント上書き) を解決して `AwsClientBuilder` に適用してくれる。
 * これを再利用することで、`SfnAsyncClient` も他の AWS クライアントと全く同じプロパティ
 * (`spring.cloud.aws.*`) だけで一貫して local/dev/stg/prod を切り替えられる。
 *
 * この Bean で組み立てた `SfnAsyncClient` は、:adapter-messaging の `SfnTaskCallbackAdapter`
 * (`@Component` として既にアノテーションされておりコンポーネントスキャンで自動配線される) が
 * コンストラクタで受け取る。
 */
@Configuration
class AwsClientsConfig {
    @Bean
    fun sfnAsyncClient(configurer: AwsClientBuilderConfigurer): SfnAsyncClient = configurer.configure(SfnAsyncClient.builder()).build()
}
