package com.example.template.application.order

import arrow.core.Either
import com.example.template.application.port.OrderRepository
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.shared.OrderId

data class ListOrdersQuery(
    val after: OrderId?,
    val limit: Int,
)

data class OrderPage(
    val items: List<Order>,
    val nextCursor: OrderId?,
)

fun interface ListOrders {
    suspend operator fun invoke(query: ListOrdersQuery): Either<OrderError, OrderPage>
}

class ListOrdersService(
    private val orderRepository: OrderRepository,
) : ListOrders {
    override suspend fun invoke(query: ListOrdersQuery): Either<OrderError, OrderPage> =
        orderRepository.findPage(query.after, query.limit + 1).map { fetched ->
            val hasNext = fetched.size > query.limit
            val items = fetched.take(query.limit)
            OrderPage(items, if (hasNext) items.lastOrNull()?.id else null)
        }
}
