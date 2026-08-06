package com.example.template.domain.settlement

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.domain.error.SettlementError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderStatus

/**
 * 決済プロバイダの消込レコード1件を、対応する注文と突き合わせる純粋関数。
 *
 * I/O を一切行わない (:application / :batch がそれぞれ注文のロードと消込ファイルの読み込みを
 * 担当し、この関数はロード済みの [Order] と [SettlementRecord] を受け取るだけ)。
 * バッチジョブと Web API の双方から同じ判定ロジックを再利用できるようにするのが目的。
 *
 * この `when (order.status)` にも `else` を書いていない。理由は OrderTransitions.kt と同じ:
 * 新しい [OrderStatus] を追加したとき、消込ロジックが対応漏れのままになるのを防ぐため。
 */
fun reconcile(
    order: Order,
    record: SettlementRecord,
): Either<SettlementError, ReconciliationOutcome> =
    either {
        when (val status = order.status) {
            is OrderStatus.Draft -> ReconciliationOutcome.OrderNotSettleable(status)
            is OrderStatus.Cancelled -> ReconciliationOutcome.OrderNotSettleable(status)
            is OrderStatus.PendingPayment -> {
                val expected = order.total().mapLeft { SettlementError.MalformedRecord(it.message) }.bind()
                if (expected == record.settledAmount) {
                    ReconciliationOutcome.Matched
                } else {
                    ReconciliationOutcome.AmountMismatch(expected = expected, actual = record.settledAmount)
                }
            }
            is OrderStatus.Paid -> ReconciliationOutcome.AlreadySettled
            is OrderStatus.Fulfilling -> ReconciliationOutcome.AlreadySettled
            is OrderStatus.Shipped -> ReconciliationOutcome.AlreadySettled
            is OrderStatus.Delivered -> ReconciliationOutcome.AlreadySettled
            is OrderStatus.Refunded -> ReconciliationOutcome.AlreadySettled
        }
    }
