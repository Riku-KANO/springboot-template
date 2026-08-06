package com.example.template.adapter.persistence.settlement

import arrow.core.Either
import com.example.template.adapter.persistence.bindNullable
import com.example.template.adapter.persistence.describeForRepository
import com.example.template.adapter.persistence.order.StatusColumns
import com.example.template.adapter.persistence.order.toColumns
import com.example.template.application.port.SettlementRepository
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Instant

private const val MATCHED = "MATCHED"
private const val ALREADY_SETTLED = "ALREADY_SETTLED"
private const val AMOUNT_MISMATCH = "AMOUNT_MISMATCH"
private const val ORDER_NOT_SETTLEABLE = "ORDER_NOT_SETTLEABLE"

private const val INSERT_SETTLEMENT_SQL = """
    INSERT INTO settlements (
        order_id, settled_amount_minor, settled_amount_currency, settled_at,
        provider_transaction_id, outcome_type, recorded_at
    ) VALUES (
        :orderId, :settledAmountMinor, :settledAmountCurrency, :settledAt,
        :providerTransactionId, :outcomeType, :recordedAt
    )
"""

private const val INSERT_SETTLEMENT_ERROR_SQL = """
    INSERT INTO settlement_errors (
        order_id, settled_amount_minor, settled_amount_currency, settled_at,
        provider_transaction_id, outcome_type,
        expected_amount_minor, expected_amount_currency, actual_amount_minor, actual_amount_currency,
        order_status_type, order_status_paid_at, order_status_fulfilling_started_at,
        order_status_tracking_number, order_status_delivered_at, order_status_cancelled_reason,
        order_status_refunded_at, order_status_refunded_amount_minor, order_status_refunded_amount_currency,
        recorded_at
    ) VALUES (
        :orderId, :settledAmountMinor, :settledAmountCurrency, :settledAt,
        :providerTransactionId, :outcomeType,
        :expectedAmountMinor, :expectedAmountCurrency, :actualAmountMinor, :actualAmountCurrency,
        :orderStatusType, :orderStatusPaidAt, :orderStatusFulfillingStartedAt,
        :orderStatusTrackingNumber, :orderStatusDeliveredAt, :orderStatusCancelledReason,
        :orderStatusRefundedAt, :orderStatusRefundedAmountMinor, :orderStatusRefundedAmountCurrency,
        :recordedAt
    )
"""

/**
 * [SettlementRepository] の R2DBC 実装。
 *
 * ReconciliationOutcome (4バリアントの sealed interface) を、V3/V4 マイグレーションで
 * 分けた2テーブルのどちらに書くかで振り分ける。Matched / AlreadySettled は
 * settlements (正常系の台帳) へ、AmountMismatch / OrderNotSettleable は
 * settlement_errors (要確認キュー) へ。テーブル設計の意図は
 * V3__create_settlements.sql / V4__create_settlement_errors.sql のコメントを参照。
 *
 * `when (outcome)` に `else` を書いていないのは OrderStatusMapping.kt と同じ理由:
 * ReconciliationOutcome に将来バリアントが増えたとき、ここが確実にコンパイルエラーになる
 * ようにするため。
 *
 * recordOutcome は単一の INSERT 文で完結する (settlements か settlement_errors の
 * どちらか一方にしか書かない) ため、OrderRepositoryAdapter.save のような
 * 複数文をまたぐ独自トランザクション管理は不要。呼び出し元の ReconcileSettlementService が
 * 既に TxRunner でこの呼び出しごと包んでいる (findById -> reconcile -> recordOutcome を
 * 1トランザクションにする) ので、単一 INSERT 自体の原子性は Postgres が保証する範囲で十分。
 */
class SettlementRepositoryAdapter(
    private val databaseClient: DatabaseClient,
) : SettlementRepository {
    override suspend fun recordOutcome(
        record: SettlementRecord,
        outcome: ReconciliationOutcome,
        recordedAt: Instant,
    ): Either<SettlementError, Unit> =
        // ##### 例外の世界から Either の世界への変換境界 #####
        Either
            .catch {
                when (outcome) {
                    is ReconciliationOutcome.Matched -> insertSettlement(record, MATCHED, recordedAt)
                    is ReconciliationOutcome.AlreadySettled -> insertSettlement(record, ALREADY_SETTLED, recordedAt)
                    is ReconciliationOutcome.AmountMismatch ->
                        insertSettlementError(
                            record = record,
                            recordedAt = recordedAt,
                            outcomeType = AMOUNT_MISMATCH,
                            expectedAmountMinor = outcome.expected.amount.value,
                            expectedAmountCurrency = outcome.expected.currency.currencyCode,
                            actualAmountMinor = outcome.actual.amount.value,
                            actualAmountCurrency = outcome.actual.currency.currencyCode,
                            orderStatusColumns = null,
                        )
                    is ReconciliationOutcome.OrderNotSettleable ->
                        insertSettlementError(
                            record = record,
                            recordedAt = recordedAt,
                            outcomeType = ORDER_NOT_SETTLEABLE,
                            expectedAmountMinor = null,
                            expectedAmountCurrency = null,
                            actualAmountMinor = null,
                            actualAmountCurrency = null,
                            orderStatusColumns = outcome.status.toColumns(),
                        )
                }
            }.mapLeft { SettlementError.InfrastructureFailure(it.describeForRepository()) }

    private suspend fun insertSettlement(
        record: SettlementRecord,
        outcomeType: String,
        recordedAt: Instant,
    ) {
        databaseClient
            .sql(INSERT_SETTLEMENT_SQL)
            .bind("orderId", record.orderId.value)
            .bind("settledAmountMinor", record.settledAmount.amount.value)
            .bind("settledAmountCurrency", record.settledAmount.currency.currencyCode)
            .bind("settledAt", record.settledAt)
            .bind("providerTransactionId", record.providerTransactionId)
            .bind("outcomeType", outcomeType)
            .bind("recordedAt", recordedAt)
            .fetch()
            .awaitRowsUpdated()
    }

    @Suppress("LongParameterList")
    private suspend fun insertSettlementError(
        record: SettlementRecord,
        recordedAt: Instant,
        outcomeType: String,
        expectedAmountMinor: Long?,
        expectedAmountCurrency: String?,
        actualAmountMinor: Long?,
        actualAmountCurrency: String?,
        orderStatusColumns: StatusColumns?,
    ) {
        databaseClient
            .sql(INSERT_SETTLEMENT_ERROR_SQL)
            .bind("orderId", record.orderId.value)
            .bind("settledAmountMinor", record.settledAmount.amount.value)
            .bind("settledAmountCurrency", record.settledAmount.currency.currencyCode)
            .bind("settledAt", record.settledAt)
            .bind("providerTransactionId", record.providerTransactionId)
            .bind("outcomeType", outcomeType)
            .bindNullable("expectedAmountMinor", expectedAmountMinor, Long::class.javaObjectType)
            .bindNullable("expectedAmountCurrency", expectedAmountCurrency, String::class.java)
            .bindNullable("actualAmountMinor", actualAmountMinor, Long::class.javaObjectType)
            .bindNullable("actualAmountCurrency", actualAmountCurrency, String::class.java)
            .bindNullable("orderStatusType", orderStatusColumns?.type, String::class.java)
            .bindNullable("orderStatusPaidAt", orderStatusColumns?.paidAt, Instant::class.java)
            .bindNullable("orderStatusFulfillingStartedAt", orderStatusColumns?.fulfillingStartedAt, Instant::class.java)
            .bindNullable("orderStatusTrackingNumber", orderStatusColumns?.trackingNumber, String::class.java)
            .bindNullable("orderStatusDeliveredAt", orderStatusColumns?.deliveredAt, Instant::class.java)
            .bindNullable("orderStatusCancelledReason", orderStatusColumns?.cancelledReason, String::class.java)
            .bindNullable("orderStatusRefundedAt", orderStatusColumns?.refundedAt, Instant::class.java)
            .bindNullable("orderStatusRefundedAmountMinor", orderStatusColumns?.refundedAmountMinor, Long::class.javaObjectType)
            .bindNullable("orderStatusRefundedAmountCurrency", orderStatusColumns?.refundedAmountCurrency, String::class.java)
            .bind("recordedAt", recordedAt)
            .fetch()
            .awaitRowsUpdated()
    }
}
