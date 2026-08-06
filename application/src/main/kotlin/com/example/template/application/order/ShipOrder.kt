package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.ship
import com.example.template.domain.shared.OrderId

data class ShipOrderCommand(
    val orderId: OrderId,
    val trackingNumber: String,
)

/** 注文を配送業者に引き渡した (Fulfilling -> Shipped) ことを記録するユースケース。 */
fun interface ShipOrder {
    suspend operator fun invoke(command: ShipOrderCommand): Either<OrderError, Order>
}

/**
 * [ShipOrder] の実装。
 * 「読み込み (findById) → 保存 (save)」の2ステップがあるため、CreateOrder とは異なり
 * TxRunner でラップする (読み込んだ後に状態遷移を適用して書き戻す、という
 * read-modify-write の整合性を保つ必要があるため)。遷移そのものの可否判断
 * (Fulfilling 以外からの ship を拒否する等) は `Order.ship` (domain) の責務であり、
 * ここでは呼び出すだけ。
 */
class ShipOrderService(
    private val orderRepository: OrderRepository,
    private val txRunner: TxRunner,
) : ShipOrder {
    override suspend fun invoke(command: ShipOrderCommand): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                val shipped = order.ship(command.trackingNumber).bind()
                orderRepository.save(shipped).bind()
            }
        }
}
