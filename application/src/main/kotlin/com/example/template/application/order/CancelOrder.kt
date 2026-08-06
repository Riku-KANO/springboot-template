package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.cancel
import com.example.template.domain.shared.OrderId

data class CancelOrderCommand(
    val orderId: OrderId,
    val reason: String,
)

/** 注文をキャンセルするユースケース。Fulfilling 以降になっているとキャンセルできない (domain の判断)。 */
fun interface CancelOrder {
    suspend operator fun invoke(command: CancelOrderCommand): Either<OrderError, Order>
}

/**
 * [CancelOrder] の実装。ShipOrder と同様、読み込み→保存の2ステップなので TxRunner でラップする。
 */
class CancelOrderService(
    private val orderRepository: OrderRepository,
    private val txRunner: TxRunner,
) : CancelOrder {
    override suspend fun invoke(command: CancelOrderCommand): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                val cancelled = order.cancel(command.reason).bind()
                orderRepository.save(cancelled).bind()
            }
        }
}
