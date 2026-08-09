package com.example.template.adapter.messaging.sqs

import com.example.template.application.port.TaskCallbackPort
import com.example.template.application.port.TaskToken
import com.example.template.domain.error.SettlementError
import io.awspring.cloud.sqs.annotation.SqsListener
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import java.time.LocalDate

@ConfigurationProperties(prefix = "settlement.sqs")
@Validated
data class SettlementSqsProperties(
    @field:NotBlank val queueName: String,
)

@Configuration
@EnableConfigurationProperties(SettlementSqsProperties::class)
class SettlementSqsPropertiesConfig

/**
 * SQS で受け取った消込バッチの起動要求 `{ settlementDate, taskToken }`。
 * Step Functions のステートマシンが `sqs:sendMessage.waitForTaskToken` でこの形のメッセージを
 * 送ってくる想定 (JSON ボディはこのデータクラスのプロパティ名にそのまま対応する)。
 */
data class SettlementTaskMessage(
    val settlementDate: LocalDate,
    val taskToken: String,
)

/**
 * 消込バッチの起動要求を受け取り、Spring Batch のジョブを起動する `@SqsListener`。
 *
 * ## なぜ ECS RunTask ではなくこの SQS 経路なのか (ローカル実行可能性の要)
 * Step Functions が非同期タスクを外部に委譲する方法はいくつかあるが、AWS の実サービス構成として
 * よく使われる ECS RunTask (`.sync`/`.waitForTaskToken` 併用) は LocalStack Community 版では
 * サポートされておらず Pro 版が必要になる。一方 `sqs:sendMessage.waitForTaskToken` パターン
 * (Step Functions が SQS にメッセージを送り、ワーカーが処理後 SendTaskSuccess/SendTaskFailure で
 * コールバックする) は SQS も Step Functions も LocalStack Community でサポートされる組み合わせの
 * ため、docker compose 上の LocalStack だけでこのテンプレート全体 (Step Functions のステート
 * マシン定義から実際のバッチ実行、コールバックによる分岐まで) をオフラインで動かせる。
 * これがこのテンプレートで ECS ではなく SQS 経由の起動を選んだ理由であり、
 * :adapter-messaging がメッセージング (SQS) モジュールとして Step Functions 連携の入口を
 * 兼ねている理由でもある。
 *
 * ## :batch への依存について (モジュールグラフ上の制約)
 * settlementReconciliationJob の実体は :batch モジュールが定義するが、
 * settings.gradle.kts のモジュールグラフには :adapter-messaging -> :batch という project 依存は
 * 存在せず、本チャンクの守備範囲 (:adapter-messaging と :batch のみ変更可) では追加できない
 * (:application に新しいポートを起こせば project 依存なしで解決できるが、それは :application の
 * 変更を意味し同じく守備範囲外)。そこで Spring Batch という共通のサードパーティライブラリの型
 * ([Job] / [JobOperator]) だけに依存し、実際の Bean 解決は :bootstrap が組み立てる単一の
 * ApplicationContext (:adapter-messaging と :batch の両方が乗る) に委ねている
 * (build.gradle.kts のコメントに詳細を記載)。
 *
 * ## ジョブ起動失敗と、ジョブ自体の失敗の切り分け
 * [JobOperator.start] 自体が例外を投げるケース (二重起動、パラメータ不正等) は、
 * ジョブが一度も実行されずに終わる = :batch の SettlementJobListener が一切呼ばれないケースなので、
 * ここで直接 [TaskCallbackPort.notifyFailure] を呼んで Step Functions にエラーを伝える。
 * 一方、ジョブが起動できた後の実行結果 (正常終了/不一致あり/レコード単位の失敗) は
 * SettlementJobListener.afterJob が責任を持つ (このリスナーはその結果を待たない/知らない)。
 */
@Component
class SettlementTaskListener(
    private val jobOperator: JobOperator,
    @param:Qualifier("settlementReconciliationJob") private val settlementReconciliationJob: Job,
    private val taskCallbackPort: TaskCallbackPort,
) {
    @SqsListener("\${settlement.sqs.queue-name}")
    fun onSettlementTask(message: SettlementTaskMessage) {
        val taskToken = TaskToken(message.taskToken)
        val jobParameters =
            JobParametersBuilder()
                .addLocalDate("settlementDate", message.settlementDate)
                .addString("taskToken", message.taskToken)
                .toJobParameters()

        runCatching { jobOperator.start(settlementReconciliationJob, jobParameters) }
            .onFailure { throwable -> reportLaunchFailure(taskToken, throwable) }
    }

    /**
     * ジョブの「起動」自体が失敗した場合の Step Functions への通知。
     * SQS リスナーのコンテナスレッドはイベントループではなく専用のワーカースレッドプールなので、
     * ここで runBlocking を使って TaskCallbackPort (suspend) を呼び出しても
     * リアクティブスタックのスループットには影響しない (:batch の各コンポーネントで
     * runBlocking を使う際の判断基準と同じ)。
     */
    private fun reportLaunchFailure(
        taskToken: TaskToken,
        throwable: Throwable,
    ) {
        logger.error("failed to start settlement reconciliation job for taskToken={}", taskToken.value, throwable)
        val cause = SettlementError.InfrastructureFailure(throwable.message ?: "failed to start settlement job")
        runBlocking {
            taskCallbackPort
                .notifyFailure(taskToken, cause)
                .onLeft { callbackError -> logger.error("failed to notify Step Functions of job launch failure: {}", callbackError) }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SettlementTaskListener::class.java)
    }
}
