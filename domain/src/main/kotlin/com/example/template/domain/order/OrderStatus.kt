package com.example.template.domain.order

import com.example.template.domain.shared.Money
import java.time.Instant

/**
 * 注文のライフサイクルを表現する状態機械。
 *
 * sealed interface にしている理由は単なる列挙のためではなく、[OrderTransitions.kt] の
 * 遷移関数が `when` で全サブタイプを網羅することをコンパイラに強制させるため。
 * 状態を追加・削除する際は、まずこのファイルを直せば、遷移関数側の対応漏れは
 * すべてコンパイルエラーとして検出される (詳細は OrderTransitions.kt のコメント参照)。
 */
sealed interface OrderStatus {
    /** 顧客がまだ注文を確定していない下書き状態。 */
    data object Draft : OrderStatus

    /** 注文は確定したが、決済の完了をまだ確認していない状態。 */
    data object PendingPayment : OrderStatus

    /** 決済が完了した状態。 */
    data class Paid(
        val paidAt: Instant,
    ) : OrderStatus

    /** 出荷準備 (ピッキング・梱包等) に着手した状態。 */
    data class Fulfilling(
        val startedAt: Instant,
    ) : OrderStatus

    /** 配送業者に引き渡し済みの状態。 */
    data class Shipped(
        val trackingNumber: String,
    ) : OrderStatus

    /** 顧客への配達が完了した状態。 */
    data class Delivered(
        val deliveredAt: Instant,
    ) : OrderStatus

    /** 何らかの理由でキャンセルされた状態。 */
    data class Cancelled(
        val reason: String,
    ) : OrderStatus

    /** 返金済みの状態。 */
    data class Refunded(
        val refundedAt: Instant,
        val amount: Money,
    ) : OrderStatus
}
