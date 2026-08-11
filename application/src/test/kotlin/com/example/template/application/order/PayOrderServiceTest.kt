package com.example.template.application.order

import arrow.core.Either
import arrow.core.nonEmptyListOf
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.PaymentAttemptStatus
import com.example.template.application.port.PaymentCharge
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.application.testsupport.FakePaymentAttemptRepository
import com.example.template.application.testsupport.FakePaymentGatewayPort
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
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

/**
 * PayOrder の主要な失敗経路 (注文なし・遷移不可・決済失敗) を検証しつつ、TxRunner の意味論
 * (「block が Left を返したら、それ以降の副作用 = repository.save は起きていない」) を
 * RecordingTxRunner + FakeOrderRepository の組み合わせで確認する。
 */
class PayOrderServiceTest {
    @Test
    fun `PendingPayment の注文を決済確定して保存する`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.PendingPayment))
            val paidAt = Instant.parse("2026-01-01T00:00:00Z")
            val gateway = FakePaymentGatewayPort(Either.Right(PaymentCharge(paidAt, "txn-1")))
            val txRunner = RecordingTxRunner()
            val service = PayOrderService(repository, FakePaymentAttemptRepository(), gateway, txRunner)

            val paid = service.invoke(PayOrderCommand(OrderId.create("order-1").shouldBeRight())).shouldBeRight()

            assertEquals(OrderStatus.Paid(paidAt), paid.status)
            assertEquals(1, gateway.invocationCount)
            assertEquals(1, repository.savedOrders.size)
            assertEquals(3, txRunner.invocationCount)
            assertEquals(false, txRunner.lastResultWasLeft)
        }

    @Test
    fun `注文が見つからなければ OrderNotFound を返し 決済ゲートウェイも save も呼ばれない`() =
        runTest {
            val repository = FakeOrderRepository(initial = null)
            val gateway = FakePaymentGatewayPort(Either.Right(PaymentCharge(Instant.now(), "txn-1")))
            val txRunner = RecordingTxRunner()
            val service = PayOrderService(repository, FakePaymentAttemptRepository(), gateway, txRunner)

            service.invoke(PayOrderCommand(OrderId.create("missing").shouldBeRight())).shouldBeLeftOfType<OrderError.OrderNotFound>()

            assertEquals(0, gateway.invocationCount)
            assertTrue(repository.savedOrders.isEmpty())
            assertEquals(true, txRunner.lastResultWasLeft)
        }

    @Test
    fun `既に Paid の注文を支払おうとすると InvalidTransition になり save は呼ばれない (Left はコミットしない)`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.Paid(Instant.now())))
            val gateway = FakePaymentGatewayPort(Either.Right(PaymentCharge(Instant.now(), "txn-1")))
            val txRunner = RecordingTxRunner()
            val service = PayOrderService(repository, FakePaymentAttemptRepository(), gateway, txRunner)

            service.invoke(PayOrderCommand(OrderId.create("order-1").shouldBeRight())).shouldBeLeftOfType<OrderError.InvalidTransition>()

            // 遷移可能性は外部決済より先に検証するため、請求自体が行われない。
            assertEquals(0, gateway.invocationCount)
            assertTrue(repository.savedOrders.isEmpty())
            assertEquals(true, txRunner.lastResultWasLeft)
        }

    @Test
    fun `決済ゲートウェイが利用不可なら PaymentGatewayUnavailable を返し save は呼ばれない`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.PendingPayment))
            val gateway = FakePaymentGatewayPort(Either.Left(OrderError.PaymentGatewayUnavailable("timeout")))
            val txRunner = RecordingTxRunner()
            val service = PayOrderService(repository, FakePaymentAttemptRepository(), gateway, txRunner)

            service
                .invoke(PayOrderCommand(OrderId.create("order-1").shouldBeRight()))
                .shouldBeLeftOfType<OrderError.PaymentGatewayUnavailable>()

            assertTrue(repository.savedOrders.isEmpty())
            assertEquals(1, txRunner.invocationCount)
            assertEquals(false, txRunner.lastResultWasLeft)
        }

    @Test
    fun `同じ注文の再実行は保存済み結果を返し 二重請求しない`() =
        runTest {
            val repository = FakeOrderRepository(orderWith(OrderStatus.PendingPayment))
            val attempts = FakePaymentAttemptRepository()
            val paidAt = Instant.parse("2026-01-01T00:00:00Z")
            val gateway = FakePaymentGatewayPort(Either.Right(PaymentCharge(paidAt, "txn-1")))
            val txRunner = RecordingTxRunner()
            val service = PayOrderService(repository, attempts, gateway, txRunner)
            val command = PayOrderCommand(OrderId.create("order-1").shouldBeRight())

            service.invoke(command).shouldBeRight()
            val replay = service.invoke(command).shouldBeRight()

            assertEquals(OrderStatus.Paid(paidAt), replay.status)
            assertEquals(1, gateway.invocationCount)
            assertEquals(listOf("order-payment:order-1"), gateway.receivedIdempotencyKeys.map { it.value })
            assertEquals(1, repository.savedOrders.size)
        }

    @Test
    fun `決済後の注文更新に失敗しても 成功した決済試行は失われない`() =
        runTest {
            val delegate = FakeOrderRepository(orderWith(OrderStatus.PendingPayment))
            val repository =
                object : OrderRepository by delegate {
                    override suspend fun save(order: Order): Either<OrderError, Order> =
                        Either.Left(OrderError.ConcurrentModification(order.id))
                }
            val attempts = FakePaymentAttemptRepository()
            val charge = PaymentCharge(Instant.parse("2026-01-01T00:00:00Z"), "txn-1")
            val service =
                PayOrderService(
                    repository,
                    attempts,
                    FakePaymentGatewayPort(Either.Right(charge)),
                    RecordingTxRunner(),
                )
            val orderId = OrderId.create("order-1").shouldBeRight()

            service.invoke(PayOrderCommand(orderId)).shouldBeLeftOfType<OrderError.ConcurrentModification>()

            val stored = attempts.findByOrderId(orderId).shouldBeRight()
            assertEquals(PaymentAttemptStatus.Succeeded(charge), stored?.status)
        }

    @Test
    fun `RecordingTxRunner は初期状態では何も記録していない`() {
        val txRunner = RecordingTxRunner()
        assertFalse(txRunner.invocationCount > 0)
        assertNull(txRunner.lastResultWasLeft)
    }
}
