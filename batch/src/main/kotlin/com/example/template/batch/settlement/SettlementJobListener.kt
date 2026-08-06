package com.example.template.batch.settlement

import com.example.template.application.port.TaskCallbackPort
import com.example.template.application.port.TaskToken
import com.example.template.application.settlement.SettlementReport
import com.example.template.domain.error.SettlementError
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.JobExecution
import org.springframework.batch.core.listener.JobExecutionListener
import org.springframework.stereotype.Component
import java.time.Clock

/**
 * ジョブ実行1回分の結果を Step Functions へ報告し、`.waitForTaskToken` のループを閉じる
 * JobExecutionListener。
 *
 * ## 通知の使い分け (notifySuccess と notifyFailure の境界線)
 * [SettlementReport.verdict] が MatchedAll / HasDiscrepancies / Failed のいずれであっても、
 * 「ジョブというタスク自体は完走した」ので notifySuccess でレポートを送る (Step Functions
 * 側のステートマシンが verdict を見て次の分岐 (人手確認フローに回す等) を決める設計。
 * SettlementReport の KDoc 参照)。notifyFailure を使うのは「ジョブそのものが致命的に落ちた」
 * (消込ファイルが読めない等、SettlementRecordItemReader が例外を投げてステップが FAILED に
 * なったケース) だけであり、レコード単位の失敗 (SettlementReport.failedCount) はあくまで
 * notifySuccess で送るレポートの一部として表現される。この2つを混同すると、
 * Step Functions 側は「一部レコードが失敗しただけ」なのか「バッチ自体が動かなかった」のかを
 * 区別できなくなってしまう。
 *
 * ## taskToken が無い場合
 * ローカル動作確認やリランなど、Step Functions を経由しない手動実行では taskToken
 * job parameter が存在しない。その場合はそもそも `.waitForTaskToken` で待っている相手がいないので、
 * コールバックは行わない (結果はログと DB のテーブルで確認する)。
 */
@Component
class SettlementJobListener(
    private val settlementItemWriter: SettlementItemWriter,
    private val taskCallbackPort: TaskCallbackPort,
    private val clock: Clock = Clock.systemUTC(),
) : JobExecutionListener {
    override fun afterJob(jobExecution: JobExecution) {
        val rawTaskToken = jobExecution.jobParameters.getString("taskToken")
        if (rawTaskToken == null) {
            logger.info("settlement reconciliation job finished with status={} (no taskToken, skipping callback)", jobExecution.status)
            return
        }
        val taskToken = TaskToken(rawTaskToken)

        runBlocking {
            val callbackResult =
                if (jobExecution.status == BatchStatus.COMPLETED) {
                    val report = SettlementReport.summarize(clock.instant(), settlementItemWriter.snapshotResults())
                    taskCallbackPort.notifySuccess(taskToken, report)
                } else {
                    val cause =
                        jobExecution.allFailureExceptions.firstOrNull()?.message
                            ?: "settlement job did not complete (status=${jobExecution.status})"
                    taskCallbackPort.notifyFailure(taskToken, SettlementError.InfrastructureFailure(cause))
                }

            callbackResult.onLeft { error -> logger.error("failed to notify Step Functions of job outcome: {}", error) }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SettlementJobListener::class.java)
    }
}
