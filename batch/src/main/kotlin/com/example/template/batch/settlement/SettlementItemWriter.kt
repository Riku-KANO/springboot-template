package com.example.template.batch.settlement

import arrow.core.Either
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.OffsetDateTime

/**
 * SettlementItemProcessor が返した Either を書き分ける ItemWriter。
 *
 * 「ジョブ全体を個別レコードの失敗で止めない (skip-and-report)」の実体はここにある。
 * Either を exhaustive に when 分岐するだけで、成功/失敗のどちらであっても例外を投げずに
 * 対応するテーブルへ書き込む。Spring Batch の SkipPolicy/SkipListener の出番が無いのは、
 * 「失敗」が例外ではなく型 (Either.Left) として表現されているため、そもそもステップの
 * 実行を中断させる例外自体が発生しないからである。
 *
 * ## 実行結果の集計について (StepExecutionListener を実装している理由)
 * SettlementJobListener (afterJob) は最終的に SettlementReport.summarize を使って
 * このジョブ全体のサマリを組み立てるが、そのためには全チャンク分の Either 結果のリストが要る。
 * Spring Batch の ExecutionContext (StepExecution/JobExecution に保持できる状態) は
 * JobRepository に永続化される = シリアライズされる前提の仕組みであり、
 * Either や SettlementError を含む任意長のリストをそこに詰めるのはシリアライズ経路的に
 * 適さない (Jackson 3 と AWS SDK 内蔵 Jackson 2 の混在などバージョン起因の問題も招きやすい)。
 * そのためこのリストは JVM 内で完結する単純なフィールドとして持ち、SettlementJobListener に
 * このインスタンスを直接注入して afterJob から参照してもらう設計にした。
 * beforeStep でリストをクリアするのは、同一 JVM 内でジョブが複数回実行された場合に
 * 前回実行分の結果が混入しないようにするため (このクラスは Spring のデフォルトスコープ =
 * シングルトンであるため、明示的にクリアしないと状態が残ってしまう)。
 */
@Component
class SettlementItemWriter(
    private val jdbcTemplate: JdbcTemplate,
    private val clock: Clock = Clock.systemUTC(),
) : ItemWriter<Either<SettlementError, ReconciledSettlement>>,
    StepExecutionListener {
    private val results = mutableListOf<Either<SettlementError, ReconciliationOutcome>>()

    override fun beforeStep(stepExecution: StepExecution) {
        SettlementBatchSchema.ensureCreated(jdbcTemplate)
        results.clear()
    }

    override fun write(chunk: Chunk<out Either<SettlementError, ReconciledSettlement>>) {
        for (item in chunk) {
            when (item) {
                is Either.Right -> {
                    insertResult(item.value)
                    results.add(Either.Right(item.value.outcome))
                }
                is Either.Left -> {
                    insertError(item.value)
                    results.add(Either.Left(item.value))
                }
            }
        }
    }

    /** afterJob からこのステップで蓄積した全件の結果を参照するためのスナップショット。 */
    fun snapshotResults(): List<Either<SettlementError, ReconciliationOutcome>> = results.toList()

    private fun insertResult(reconciled: ReconciledSettlement) {
        val (record, outcome) = reconciled
        val (outcomeLabel, expectedMinor, actualMinor) =
            when (outcome) {
                is ReconciliationOutcome.Matched -> Triple("MATCHED", null, null)
                is ReconciliationOutcome.AmountMismatch ->
                    Triple("AMOUNT_MISMATCH", outcome.expected.amount.value, outcome.actual.amount.value)
                is ReconciliationOutcome.OrderNotSettleable -> Triple("ORDER_NOT_SETTLEABLE", null, null)
                is ReconciliationOutcome.AlreadySettled -> Triple("ALREADY_SETTLED", null, null)
            }

        jdbcTemplate.update(
            """
            INSERT INTO batch_settlement_results
                (order_id, provider_transaction_id, settled_amount_minor, currency, settled_at,
                 outcome, expected_amount_minor, actual_amount_minor, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            record.orderId.value,
            record.providerTransactionId,
            record.settledAmount.amount.value,
            record.settledAmount.currency.currencyCode,
            OffsetDateTime.ofInstant(record.settledAt, clock.zone),
            outcomeLabel,
            expectedMinor,
            actualMinor,
            OffsetDateTime.now(clock),
        )
    }

    private fun insertError(error: SettlementError) {
        val orderId =
            when (error) {
                is SettlementError.UnknownOrder -> error.orderId.value
                is SettlementError.MalformedRecord -> null
                is SettlementError.InfrastructureFailure -> null
            }
        val errorType =
            when (error) {
                is SettlementError.MalformedRecord -> "MALFORMED_RECORD"
                is SettlementError.UnknownOrder -> "UNKNOWN_ORDER"
                is SettlementError.InfrastructureFailure -> "INFRASTRUCTURE_FAILURE"
            }

        jdbcTemplate.update(
            """
            INSERT INTO batch_settlement_errors (order_id, error_type, message, recorded_at)
            VALUES (?, ?, ?, ?)
            """,
            orderId,
            errorType,
            error.message,
            OffsetDateTime.now(clock),
        )
    }
}
