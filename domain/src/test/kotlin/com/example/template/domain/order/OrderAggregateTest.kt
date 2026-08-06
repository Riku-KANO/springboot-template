package com.example.template.domain.order

import arrow.core.nonEmptyListOf
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.customerId
import com.example.template.domain.testfixtures.orderId
import com.example.template.domain.testfixtures.orderLine
import com.example.template.domain.testfixtures.shippingAddress
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import io.kotest.property.Arb
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")

private val FIXED_ADDRESS =
    ShippingAddress(
        recipientName = "Taro Yamada",
        postalCode = "1000001",
        prefecture = "Tokyo",
        city = "Chiyoda",
        addressLine1 = "1-1-1",
    )

class OrderAggregateTest {
    @Test
    fun `create は SKU が重複していなければ Draft 状態の Order を作る`() =
        runTest {
            checkAll(Arb.orderId(), Arb.customerId(), Arb.orderLine(), Arb.shippingAddress()) { id, customerId, line, address ->
                val order = Order.create(id, customerId, nonEmptyListOf(line), address).shouldBeRight()
                assertEquals(OrderStatus.Draft, order.status)
            }
        }

    @Test
    fun `create は SKU が重複していると集約レベルの InvalidOrderLine を返す (フェイルファスト)`() =
        runTest {
            checkAll(Arb.orderId(), Arb.customerId(), Arb.orderLine(), Arb.shippingAddress()) { id, customerId, line, address ->
                val duplicated = nonEmptyListOf(line, line)
                Order.create(id, customerId, duplicated, address).shouldBeLeftOfType<OrderError.InvalidOrderLine>()
            }
        }

    @Test
    fun `total は全明細の (単価 x 数量) の合計になる`() {
        val lineA = OrderLine.createFailFast("SKU-A", 2, 100, JPY).shouldBeRight()
        val lineB = OrderLine.createFailFast("SKU-B", 3, 50, JPY).shouldBeRight()

        val order =
            Order
                .create(
                    id = OrderId.create("order-1").shouldBeRight(),
                    customerId = CustomerId.create("customer-1").shouldBeRight(),
                    lines = nonEmptyListOf(lineA, lineB),
                    address = FIXED_ADDRESS,
                ).shouldBeRight()

        val total = order.total().shouldBeRight()
        // 2*100 + 3*50 = 350
        assertEquals(350L, total.amount.value)
        assertEquals(JPY, total.currency)
    }
}
