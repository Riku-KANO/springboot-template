package com.example.template.batch.settlement

import arrow.core.Either
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.listener.StepExecutionListener
import org.springframework.batch.core.step.StepExecution
import org.springframework.batch.infrastructure.item.Chunk
import org.springframework.batch.infrastructure.item.ItemWriter
import org.springframework.beans.factory.annotation.Value
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
 * Writer は StepScope とし、共有可変リストを持たない。各監査行へ jobInstanceId を記録し、
 * JobListener はDBから集計する。これにより同一JVMでジョブが並行しても結果が混ざらず、
 * 失敗後に別JobExecutionとして再開しても同じJobInstanceの結果を再利用できる。
 */
@Component
@StepScope
class SettlementItemWriter(
    private val jdbcTemplate: JdbcTemplate,
    @param:Value("#{jobInstanceId}") private val jobInstanceId: Long,
    private val clock: Clock = Clock.systemUTC(),
) : ItemWriter<Either<SettlementError, ReconciledSettlement>>,
    StepExecutionListener {
    override fun beforeStep(stepExecution: StepExecution) {
        SettlementBatchSchema.ensureCreated(jdbcTemplate)
    }

    override fun write(chunk: Chunk<out Either<SettlementError, ReconciledSettlement>>) {
        for (item in chunk) {
            when (item) {
                is Either.Right -> {
                    insertResult(item.value)
                }
                is Either.Left -> {
                    insertError(item.value)
                }
            }
        }
    }

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
                (job_instance_id, order_id, provider_transaction_id, settled_amount_minor, currency, settled_at,
                 outcome, expected_amount_minor, actual_amount_minor, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (job_instance_id, provider_transaction_id) DO NOTHING
            """,
            jobInstanceId,
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
            INSERT INTO batch_settlement_errors (job_instance_id, order_id, error_type, message, recorded_at)
            VALUES (?, ?, ?, ?, ?)
            """,
            jobInstanceId,
            orderId,
            errorType,
            error.message,
            OffsetDateTime.now(clock),
        )
    }
}
