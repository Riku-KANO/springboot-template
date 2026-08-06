package com.example.template.application.order

import arrow.core.nonEmptyListOf
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")

/** FindOrder は「読み取り専用ユースケースは TxRunner を使わない」ことの対照実験でもある。 */
class FindOrderServiceTest {
    @Test
    fun `存在する注文を返す`() =
        runTest {
            val line = OrderLine.createFailFast("SKU-1", 1, 1_000, JPY).shouldBeRight()
            val order =
                Order(
                    id = OrderId.create("order-1").shouldBeRight(),
                    customerId = CustomerId.create("customer-1").shouldBeRight(),
                    lines = nonEmptyListOf(line),
                    address =
                        ShippingAddress(
                            recipientName = "Taro",
                            postalCode = "100-0001",
                            prefecture = "Tokyo",
                            city = "Chiyoda",
                            addressLine1 = "1-1-1",
                        ),
                    status = OrderStatus.Draft,
                )
            val repository = FakeOrderRepository(order)
            val service = FindOrderService(repository)

            val found = service.invoke(FindOrderQuery(order.id)).shouldBeRight()

            assertEquals(order, found)
        }

    @Test
    fun `存在しない注文は OrderNotFound を返す`() =
        runTest {
            val repository = FakeOrderRepository(initial = null)
            val service = FindOrderService(repository)

            service.invoke(FindOrderQuery(OrderId.create("missing").shouldBeRight())).shouldBeLeftOfType<OrderError.OrderNotFound>()
        }
}
