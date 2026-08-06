package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.submitForPayment
import com.example.template.domain.shared.OrderId

data class SubmitOrderForPaymentCommand(
    val orderId: OrderId,
)

/** 注文を確定し決済待ちにする (Draft -> PendingPayment) ユースケース。 */
fun interface SubmitOrderForPayment {
    suspend operator fun invoke(command: SubmitOrderForPaymentCommand): Either<OrderError, Order>
}

/**
 * [SubmitOrderForPayment] の実装。
 * ShipOrder / CancelOrder と同じ「読み込み (findById) → 保存 (save)」の read-modify-write なので
 * TxRunner で包む。PendingPayment は付随情報を持たない状態 (OrderStatus.kt 参照) のため、
 * submitForPayment 自体は時刻等の追加入力を必要としない。
 */
class SubmitOrderForPaymentService(
    private val orderRepository: OrderRepository,
    private val txRunner: TxRunner,
) : SubmitOrderForPayment {
    override suspend fun invoke(command: SubmitOrderForPaymentCommand): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                val submitted = order.submitForPayment().bind()
                orderRepository.save(submitted).bind()
            }
        }
}
