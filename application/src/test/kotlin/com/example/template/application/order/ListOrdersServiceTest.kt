package com.example.template.application.order

import arrow.core.nonEmptyListOf
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.util.Currency

class ListOrdersServiceTest {
    private fun order(id: String): Order =
        Order(
            id = OrderId.create(id).shouldBeRight(),
            customerId = CustomerId.create("customer-1").shouldBeRight(),
            lines = nonEmptyListOf(OrderLine.createFailFast("SKU-1", 1, 100, Currency.getInstance("JPY")).shouldBeRight()),
            address = ShippingAddress("Taro", "100-0001", "Tokyo", "Chiyoda", "1-1"),
            status = OrderStatus.Draft,
        )

    @Test
    fun `limit plus one で次ページを判定し末尾IDをcursorとして返す`() =
        runTest {
            val orders = listOf(order("order-3"), order("order-1"), order("order-2"))
            val service = ListOrdersService(FakeOrderRepository(orders.first(), orders.drop(1)))

            val first = service(ListOrdersQuery(after = null, limit = 2)).shouldBeRight()
            assertEquals(listOf("order-1", "order-2"), first.items.map { it.id.value })
            assertEquals("order-2", first.nextCursor?.value)

            val second = service(ListOrdersQuery(after = first.nextCursor, limit = 2)).shouldBeRight()
            assertEquals(listOf("order-3"), second.items.map { it.id.value })
            assertNull(second.nextCursor)
        }
}
