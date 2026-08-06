package com.example.template.application.order

import arrow.core.NonEmptyList
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")

private val ADDRESS =
    ShippingAddress(
        recipientName = "Taro",
        postalCode = "100-0001",
        prefecture = "Tokyo",
        city = "Chiyoda",
        addressLine1 = "1-1-1",
    )

class CreateOrderServiceTest {
    @Test
    fun `有効なコマンドから注文を作成して保存する`() =
        runTest {
            val repository = FakeOrderRepository()
            val service = CreateOrderService(repository)

            val command =
                CreateOrderCommand
                    .create(
                        rawOrderId = "order-1",
                        rawCustomerId = "customer-1",
                        rawLines = NonEmptyList(RawOrderLine("SKU-1", 2, 500), emptyList()),
                        currency = JPY,
                        address = ADDRESS,
                    ).shouldBeRight()

            val order = service.invoke(command).shouldBeRight()

            assertEquals(OrderStatus.Draft, order.status)
            assertEquals(1, repository.savedOrders.size)
            assertEquals(order, repository.savedOrders.single())
        }

    @Test
    fun `重複した SKU を含むコマンドはフェイルファストで InvalidOrderLine を1件返す (累積バリデーションとの対比)`() =
        runTest {
            // CreateOrderCommand.create (フィールド単位) は個々の SKU の形式自体は妥当なので通過するが、
            // Order.create (集約レベル) が重複を検知してフェイルファストで打ち切る。
            // ここでは複数の問題を仕込んでも1件のエラーしか返らないことを確認し、
            // CreateOrderCommandTest の累積バリデーション (複数件返る) との違いを際立たせる。
            val repository = FakeOrderRepository()
            val service = CreateOrderService(repository)

            val command =
                CreateOrderCommand
                    .create(
                        rawOrderId = "order-1",
                        rawCustomerId = "customer-1",
                        rawLines = NonEmptyList(RawOrderLine("SKU-1", 1, 500), listOf(RawOrderLine("SKU-1", 2, 300))),
                        currency = JPY,
                        address = ADDRESS,
                    ).shouldBeRight()

            service.invoke(command).shouldBeLeftOfType<OrderError.InvalidOrderLine>()
            assert(repository.savedOrders.isEmpty()) { "重複 SKU で失敗した場合 save は呼ばれてはいけない" }
        }
}
