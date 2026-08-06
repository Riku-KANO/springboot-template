package com.example.template.application.order

import arrow.core.nonEmptyListOf
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.application.testsupport.RecordingTxRunner
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
import org.junit.jupiter.api.Assertions.assertTrue
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

class SubmitOrderForPaymentServiceTest {
    @Test
    fun `Draft の注文を確定して PendingPayment にし保存する`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.Draft))
            val txRunner = RecordingTxRunner()
            val service = SubmitOrderForPaymentService(repository, txRunner)

            val submitted =
                service
                    .invoke(SubmitOrderForPaymentCommand(OrderId.create("order-1").shouldBeRight()))
                    .shouldBeRight()

            assertEquals(OrderStatus.PendingPayment, submitted.status)
            assertEquals(1, repository.savedOrders.size)
            assertEquals(false, txRunner.lastResultWasLeft)
        }

    @Test
    fun `既に PendingPayment の注文は確定できず InvalidTransition となり save は呼ばれない`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.PendingPayment))
            val txRunner = RecordingTxRunner()
            val service = SubmitOrderForPaymentService(repository, txRunner)

            service
                .invoke(SubmitOrderForPaymentCommand(OrderId.create("order-1").shouldBeRight()))
                .shouldBeLeftOfType<OrderError.InvalidTransition>()

            assertTrue(repository.savedOrders.isEmpty())
            assertEquals(true, txRunner.lastResultWasLeft)
        }

    @Test
    fun `注文が見つからなければ OrderNotFound を返す`() =
        runTest {
            val repository = FakeOrderRepository(initial = null)
            val txRunner = RecordingTxRunner()
            val service = SubmitOrderForPaymentService(repository, txRunner)

            service
                .invoke(SubmitOrderForPaymentCommand(OrderId.create("missing").shouldBeRight()))
                .shouldBeLeftOfType<OrderError.OrderNotFound>()

            assertTrue(repository.savedOrders.isEmpty())
        }
}
