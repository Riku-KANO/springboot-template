package com.example.template.adapter.persistence.order

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import java.time.Instant
import java.util.Currency

internal const val STATUS_DRAFT: String = "DRAFT"
internal const val STATUS_PENDING_PAYMENT: String = "PENDING_PAYMENT"
internal const val STATUS_PAID: String = "PAID"
internal const val STATUS_FULFILLING: String = "FULFILLING"
internal const val STATUS_SHIPPED: String = "SHIPPED"
internal const val STATUS_DELIVERED: String = "DELIVERED"
internal const val STATUS_CANCELLED: String = "CANCELLED"
internal const val STATUS_REFUNDED: String = "REFUNDED"

/**
 * orders (および settlement_errors) テーブルの status_* 列一式。
 * [V1__create_orders.sql] の設計意図コメントを参照。1つの `StatusColumns` を
 * orders テーブルの永続化と settlement_errors.order_status_* の永続化の両方で
 * 使い回すことで、「OrderStatus をどう列に落とすか」の判断を1箇所に閉じ込めている。
 */
internal data class StatusColumns(
    val type: String,
    val paidAt: Instant? = null,
    val fulfillingStartedAt: Instant? = null,
    val trackingNumber: String? = null,
    val deliveredAt: Instant? = null,
    val cancelledReason: String? = null,
    val refundedAt: Instant? = null,
    val refundedAmountMinor: Long? = null,
    val refundedAmountCurrency: String? = null,
)

/**
 * OrderStatus -> StatusColumns。
 *
 * `when` に `else` を書いていない。OrderStatus は sealed interface なので、
 * 将来9番目のバリアントが追加された瞬間にここがコンパイルエラーになり、
 * 「新しい状態を永続化列にどう落とすか決め忘れる」ことを構造的に防ぐ
 * (domain/order/OrderTransitions.kt と同じ狙い)。
 */
internal fun OrderStatus.toColumns(): StatusColumns =
    when (this) {
        is OrderStatus.Draft -> StatusColumns(type = STATUS_DRAFT)
        is OrderStatus.PendingPayment -> StatusColumns(type = STATUS_PENDING_PAYMENT)
        is OrderStatus.Paid -> StatusColumns(type = STATUS_PAID, paidAt = paidAt)
        is OrderStatus.Fulfilling -> StatusColumns(type = STATUS_FULFILLING, fulfillingStartedAt = startedAt)
        is OrderStatus.Shipped -> StatusColumns(type = STATUS_SHIPPED, trackingNumber = trackingNumber)
        is OrderStatus.Delivered -> StatusColumns(type = STATUS_DELIVERED, deliveredAt = deliveredAt)
        is OrderStatus.Cancelled -> StatusColumns(type = STATUS_CANCELLED, cancelledReason = reason)
        is OrderStatus.Refunded ->
            StatusColumns(
                type = STATUS_REFUNDED,
                refundedAt = refundedAt,
                refundedAmountMinor = amount.amount.value,
                refundedAmountCurrency = amount.currency.currencyCode,
            )
    }

/**
 * OrderRow (行由来の生データ) -> OrderStatus。
 *
 * こちらは discriminator (`row.statusType`) が単なる文字列であり、DB の中身は
 * 理論上何でも入りうる。sealed interface に対する `when` の網羅性チェックは
 * 「Kotlin の型」に対してしか働かないため、素の String に対する `when` は
 * コンパイラに網羅性を保証してもらえず、`else` 分岐が必須になる
 * (課題文が「ADT -> row 方向だけ else 無し」を要求しているのはこのため)。
 * `else` では「想定外の status_type が来た」を [OrderError.RepositoryUnavailable] として
 * 表明する。ここが「壊れた行」を例外ではなく型でハンドリングする境界そのもの。
 */
internal fun decodeOrderStatus(row: OrderRow): Either<OrderError, OrderStatus> =
    either {
        when (row.statusType) {
            STATUS_DRAFT -> OrderStatus.Draft
            STATUS_PENDING_PAYMENT -> OrderStatus.PendingPayment
            STATUS_PAID -> {
                val paidAt = ensureNotNull(row.statusPaidAt) { corruptedOrderRow(row.id, "status_paid_at is NULL for PAID") }
                OrderStatus.Paid(paidAt)
            }
            STATUS_FULFILLING -> {
                val startedAt =
                    ensureNotNull(row.statusFulfillingStartedAt) {
                        corruptedOrderRow(row.id, "status_fulfilling_started_at is NULL for FULFILLING")
                    }
                OrderStatus.Fulfilling(startedAt)
            }
            STATUS_SHIPPED -> {
                val trackingNumber =
                    ensureNotNull(row.statusTrackingNumber) {
                        corruptedOrderRow(row.id, "status_tracking_number is NULL for SHIPPED")
                    }
                OrderStatus.Shipped(trackingNumber)
            }
            STATUS_DELIVERED -> {
                val deliveredAt =
                    ensureNotNull(row.statusDeliveredAt) {
                        corruptedOrderRow(row.id, "status_delivered_at is NULL for DELIVERED")
                    }
                OrderStatus.Delivered(deliveredAt)
            }
            STATUS_CANCELLED -> {
                val reason =
                    ensureNotNull(row.statusCancelledReason) {
                        corruptedOrderRow(row.id, "status_cancelled_reason is NULL for CANCELLED")
                    }
                OrderStatus.Cancelled(reason)
            }
            STATUS_REFUNDED -> {
                val refundedAt =
                    ensureNotNull(row.statusRefundedAt) { corruptedOrderRow(row.id, "status_refunded_at is NULL for REFUNDED") }
                val minor =
                    ensureNotNull(row.statusRefundedAmountMinor) {
                        corruptedOrderRow(row.id, "status_refunded_amount_minor is NULL for REFUNDED")
                    }
                val currencyCode =
                    ensureNotNull(row.statusRefundedAmountCurrency) {
                        corruptedOrderRow(row.id, "status_refunded_amount_currency is NULL for REFUNDED")
                    }
                val currency =
                    Either
                        .catch { Currency.getInstance(currencyCode) }
                        .mapLeft { corruptedOrderRow(row.id, "invalid currency code: $currencyCode") }
                        .bind()
                val amount =
                    MoneyMinor
                        .create(minor)
                        .mapLeft { corruptedOrderRow(row.id, "negative status_refunded_amount_minor: $minor") }
                        .bind()
                OrderStatus.Refunded(refundedAt, Money(amount, currency))
            }
            else -> raise(corruptedOrderRow(row.id, "unknown status_type: ${row.statusType}"))
        }
    }
