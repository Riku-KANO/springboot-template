package com.example.template.adapter.messaging.sfn

import com.example.template.application.port.TaskToken
import com.example.template.domain.error.SettlementError
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import software.amazon.awssdk.services.sfn.SfnClient
import software.amazon.awssdk.services.sfn.model.ExecutionStatus
import software.amazon.awssdk.services.sqs.SqsClient
import tools.jackson.module.kotlin.jacksonObjectMapper
import java.time.Duration
import java.time.Instant

/**
 * SfnTaskCallbackAdapter が実際に Step Functions の `.waitForTaskToken` ループを閉じられることを、
 * LocalStack 上で実行される本物のステートマシンに対して検証する。
 *
 * ## テストシナリオがなぜ「ステートマシン + SQS」まで組み立てているのか
 * taskToken は Step Functions が `.waitForTaskToken` の Task ステートに到達した時にだけ払い出す
 * 実行時の値であり、テスト側で適当な文字列を捏造しても SendTaskSuccess/SendTaskFailure の
 * 呼び出し自体は成功してしまう (LocalStack が値の妥当性を強くは検証しないため)。
 * それでは「本当にこのアダプタが Step Functions のコールバックループを閉じられるか」を
 * 検証したことにならない。そこで実際に `aws-sdk:sqs:sendMessage.waitForTaskToken` 型の
 * ステートを持つステートマシンを作成し、実行を開始して SQS 経由で本物の taskToken を受け取り、
 * それに対して SendTaskSuccess/SendTaskFailure を呼び、最終的に DescribeExecution が
 * SUCCEEDED/FAILED に遷移することまで確認する。これはこのテンプレートが謳う
 * 「LocalStack Community だけで Step Functions 連携がオフライン完結する」という主張そのものの検証でもある。
 *
 * LocalStack Community は S3 / SQS / Step Functions / SSM / Secrets Manager をサポートするが
 * ECS はサポートしない (Pro 版限定)。そのためこのテンプレートが ECS RunTask ではなく
 * SQS 経由の waitForTaskToken パターンを採用している (SettlementTaskListener のコメント参照)。
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SfnTaskCallbackAdapterIntegrationTest {
    private lateinit var sfnClient: SfnClient
    private lateinit var sfnAsyncClient: SfnAsyncClient
    private lateinit var sqsClient: SqsClient
    private val objectMapper = jacksonObjectMapper()

    @BeforeAll
    fun setUpClients() {
        val credentials =
            StaticCredentialsProvider.create(AwsBasicCredentials.create(localstack.accessKey, localstack.secretKey))
        sfnClient =
            SfnClient
                .builder()
                .endpointOverride(localstack.endpoint)
                .credentialsProvider(credentials)
                .region(Region.of(localstack.region))
                .build()
        sfnAsyncClient =
            SfnAsyncClient
                .builder()
                .endpointOverride(localstack.endpoint)
                .credentialsProvider(credentials)
                .region(Region.of(localstack.region))
                .build()
        sqsClient =
            SqsClient
                .builder()
                .endpointOverride(localstack.endpoint)
                .credentialsProvider(credentials)
                .region(Region.of(localstack.region))
                .build()
    }

    @AfterAll
    fun tearDownClients() {
        sfnClient.close()
        sfnAsyncClient.close()
        sqsClient.close()
    }

    @Test
    fun `notifySuccess を呼ぶと waitForTaskToken の実行が SUCCEEDED として完了する`() {
        val queueUrl = createQueue("settlement-success-queue")
        val stateMachineArn = createWaitForTokenStateMachine("settlement-success-flow", queueUrl)
        val executionArn = sfnClient.startExecution { it.stateMachineArn(stateMachineArn).input("{}") }.executionArn()

        val taskToken = TaskToken(receiveTaskToken(queueUrl))
        val adapter = SfnTaskCallbackAdapter(sfnAsyncClient, objectMapper)

        val result = runBlocking { adapter.notifySuccess(taskToken, SettlementReportStub(matchedCount = 3, mismatchCount = 0)) }
        assertTrue(result.isRight())

        val execution = awaitExecutionStatus(executionArn, ExecutionStatus.SUCCEEDED)
        assertTrue(execution.output().orEmpty().contains("\"matchedCount\":3"))
    }

    @Test
    fun `notifyFailure を呼ぶと waitForTaskToken の実行が FAILED として完了する`() {
        val queueUrl = createQueue("settlement-failure-queue")
        val stateMachineArn = createWaitForTokenStateMachine("settlement-failure-flow", queueUrl)
        val executionArn = sfnClient.startExecution { it.stateMachineArn(stateMachineArn).input("{}") }.executionArn()

        val taskToken = TaskToken(receiveTaskToken(queueUrl))
        val adapter = SfnTaskCallbackAdapter(sfnAsyncClient, objectMapper)

        val error = SettlementError.InfrastructureFailure("settlement file unreadable")
        val result = runBlocking { adapter.notifyFailure(taskToken, error) }
        assertTrue(result.isRight())

        val execution = awaitExecutionStatus(executionArn, ExecutionStatus.FAILED)
        assertEquals("InfrastructureFailure", execution.error())
        assertEquals(error.message, execution.cause())
    }

    /**
     * CreateQueue が返す queueUrl (`http://sqs.<region>.localhost.localstack.cloud:4566/...`) は、
     * LocalStack が生成する仮想ホスト名ベースの URL であり、Testcontainers がホスト側に割り当てた
     * ランダムなマッピングポートとは食い違う (常に固定の 4566 を指してしまう) ため、テスト実行環境の
     * JVM からは到達できないことがある。そのため、実際に到達可能な localstack.endpoint を使って
     * パススタイルの URL を自前で組み立てる (LocalStack 内部では Step Functions -> SQS の統合も
     * キュー名/アカウントID の文字列一致で解決されるため、ホスト名の違いは実害がない)。
     */
    private fun createQueue(name: String): String {
        sqsClient.createQueue { it.queueName(name) }
        return "${localstack.endpoint}/000000000000/$name"
    }

    /**
     * `aws-sdk:sqs:sendMessage.waitForTaskToken` という、本番の SettlementTaskListener が待ち受ける
     * のと全く同じ種類の統合を1ステートだけ持つ、テスト専用の最小ステートマシン。
     */
    private fun createWaitForTokenStateMachine(
        name: String,
        queueUrl: String,
    ): String {
        val definition =
            """
            {
              "Comment": "settlement task waitForTaskToken test state machine",
              "StartAt": "WaitForToken",
              "States": {
                "WaitForToken": {
                  "Type": "Task",
                  "Resource": "arn:aws:states:::aws-sdk:sqs:sendMessage.waitForTaskToken",
                  "Parameters": {
                    "QueueUrl": "$queueUrl",
                    "MessageBody": {
                      "taskToken.${'$'}": "${'$'}${'$'}.Task.Token"
                    }
                  },
                  "End": true
                }
              }
            }
            """.trimIndent()

        return sfnClient
            .createStateMachine { builder ->
                builder
                    .name(name)
                    .definition(definition)
                    .roleArn("arn:aws:iam::000000000000:role/service-role/StatesExecutionRole")
            }.stateMachineArn()
    }

    private fun receiveTaskToken(queueUrl: String): String {
        val deadline = Instant.now().plus(RECEIVE_TIMEOUT)
        while (Instant.now().isBefore(deadline)) {
            val messages =
                sqsClient
                    .receiveMessage { it.queueUrl(queueUrl).maxNumberOfMessages(1).waitTimeSeconds(2) }
                    .messages()
            if (messages.isNotEmpty()) {
                val body = objectMapper.readTree(messages[0].body())
                return body.get("taskToken").asString()
            }
        }
        throw AssertionError("did not receive a task token from $queueUrl within $RECEIVE_TIMEOUT")
    }

    private fun awaitExecutionStatus(
        executionArn: String,
        expected: ExecutionStatus,
    ): software.amazon.awssdk.services.sfn.model.DescribeExecutionResponse {
        val deadline = Instant.now().plus(EXECUTION_POLL_TIMEOUT)
        var last = sfnClient.describeExecution { it.executionArn(executionArn) }
        while (last.status() != expected && Instant.now().isBefore(deadline)) {
            Thread.sleep(POLL_INTERVAL_MILLIS)
            last = sfnClient.describeExecution { it.executionArn(executionArn) }
        }
        assertEquals(expected, last.status(), "execution did not reach $expected in time (was ${last.status()})")
        return last
    }

    /** notifySuccess の payload シリアライズを検証するための最小限のスタブ。SettlementReport の代わりに使う。 */
    private data class SettlementReportStub(
        val matchedCount: Int,
        val mismatchCount: Int,
    )

    companion object {
        private val RECEIVE_TIMEOUT: Duration = Duration.ofSeconds(15)
        private val EXECUTION_POLL_TIMEOUT: Duration = Duration.ofSeconds(15)
        private const val POLL_INTERVAL_MILLIS = 300L

        @Container
        @JvmStatic
        private val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9"))
                // sts / iam を含めていないと、Step Functions の aws-sdk:sqs:sendMessage 系の統合が
                // 内部的な認証情報解決に失敗し "Service 'sts' is not enabled" という実行時エラーで
                // ステートマシンの実行自体が即座に FAILED になる (実機の docker で実際に確認した挙動)。
                .withServices("sqs", "stepfunctions", "sts", "iam")
    }
}
