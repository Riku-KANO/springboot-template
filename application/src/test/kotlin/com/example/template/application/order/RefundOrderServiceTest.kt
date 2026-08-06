package com.example.template.application.order

import arrow.core.nonEmptyListOf
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.application.testsupport.RecordingTxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
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

private fun orderWith(status: OrderStatus): Order {
    val line = OrderLine.createFailFast("SKU-1", 1, 1_000, JPY).shouldBeRight()
    return Order(
        id = OrderId.create("order-1").shouldBeRight(),
        customerId = CustomerId.create("customer-1").shouldBeRight(),
        lines = nonEmptyListOf(line),
        address = ADDRESS,
        status = status,
    )
}

class RefundOrderServiceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC)
    private val refundAmount = Money(MoneyMinor.create(1_000).shouldBeRight(), JPY)

    @Test
    fun `Paid の注文を返金して保存する`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.Paid(Instant.now())))
            val txRunner = RecordingTxRunner()
            val service = RefundOrderService(repository, txRunner, fixedClock)

            val refunded =
                service
                    .invoke(RefundOrderCommand(OrderId.create("order-1").shouldBeRight(), refundAmount))
                    .shouldBeRight()

            assertEquals(OrderStatus.Refunded(fixedClock.instant(), refundAmount), refunded.status)
            assertEquals(1, repository.savedOrders.size)
            assertEquals(false, txRunner.lastResultWasLeft)
        }

    @Test
    fun `Delivered の注文も返金できる`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.Delivered(Instant.now())))
            val txRunner = RecordingTxRunner()
            val service = RefundOrderService(repository, txRunner, fixedClock)

            val refunded =
                service
                    .invoke(RefundOrderCommand(OrderId.create("order-1").shouldBeRight(), refundAmount))
                    .shouldBeRight()

            assertEquals(OrderStatus.Refunded(fixedClock.instant(), refundAmount), refunded.status)
        }

    @Test
    fun `Draft の注文は返金できず InvalidTransition となり save は呼ばれない`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.Draft))
            val txRunner = RecordingTxRunner()
            val service = RefundOrderService(repository, txRunner, fixedClock)

            service
                .invoke(RefundOrderCommand(OrderId.create("order-1").shouldBeRight(), refundAmount))
                .shouldBeLeftOfType<OrderError.InvalidTransition>()

            assertTrue(repository.savedOrders.isEmpty())
            assertEquals(true, txRunner.lastResultWasLeft)
        }

    @Test
    fun `注文が見つからなければ OrderNotFound を返す`() =
        runTest {
            val repository = FakeOrderRepository(initial = null)
            val txRunner = RecordingTxRunner()
            val service = RefundOrderService(repository, txRunner, fixedClock)

            service
                .invoke(RefundOrderCommand(OrderId.create("missing").shouldBeRight(), refundAmount))
                .shouldBeLeftOfType<OrderError.OrderNotFound>()

            assertTrue(repository.savedOrders.isEmpty())
        }
}
