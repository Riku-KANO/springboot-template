package com.example.template.domain.settlement

import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.Money

/** [reconcile] の結果として取りうる4通りの状態。 */
sealed interface ReconciliationOutcome {
    /** 消込ファイルの金額と注文合計が一致し、支払い済みとして確定できる。 */
    data object Matched : ReconciliationOutcome

    /** 消込ファイルの金額と注文合計が一致しない。要調査。 */
    data class AmountMismatch(
        val expected: Money,
        val actual: Money,
    ) : ReconciliationOutcome

    /** 注文がそもそも消込可能な状態にない (例: まだ Draft、あるいは既に Cancelled)。 */
    data class OrderNotSettleable(
        val status: OrderStatus,
    ) : ReconciliationOutcome

    /** 注文は既に決済確認済み (Paid 以降) であり、この消込は重複処理である。 */
    data object AlreadySettled : ReconciliationOutcome
}
