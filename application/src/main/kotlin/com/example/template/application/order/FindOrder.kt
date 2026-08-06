package com.example.template.application.order

import arrow.core.Either
import com.example.template.application.port.OrderRepository
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.shared.OrderId

data class FindOrderQuery(
    val orderId: OrderId,
)

/** 注文を ID で取得する読み取り専用ユースケース。 */
fun interface FindOrder {
    suspend operator fun invoke(query: FindOrderQuery): Either<OrderError, Order>
}

/**
 * [FindOrder] の実装。単純な読み取り1回だけであり、書き込みを一切伴わないため
 * TxRunner で包む理由がない (ロールバックすべき副作用が存在しない)。
 * 「読み取り専用ユースケースはトランザクション境界を持たない」という対比を
 * PayOrder / ShipOrder / CancelOrder の実装と合わせて確認できる。
 */
class FindOrderService(
    private val orderRepository: OrderRepository,
) : FindOrder {
    override suspend fun invoke(query: FindOrderQuery): Either<OrderError, Order> = orderRepository.findById(query.orderId)
}
