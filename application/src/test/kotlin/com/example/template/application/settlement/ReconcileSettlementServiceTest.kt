package com.example.template.application.settlement

import arrow.core.Either
import arrow.core.nonEmptyListOf
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.application.testsupport.FakeSettlementRepository
import com.example.template.application.testsupport.RecordingTxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.SettlementError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
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

private fun orderWith(
    status: OrderStatus,
    unitPriceMinor: Long = 1_000,
): Order {
    val line = OrderLine.createFailFast("SKU-1", 1, unitPriceMinor, JPY).shouldBeRight()
    return Order(
        id = OrderId.create("order-1").shouldBeRight(),
        customerId = CustomerId.create("customer-1").shouldBeRight(),
        lines = nonEmptyListOf(line),
        address = ADDRESS,
        status = status,
    )
}

private fun recordFor(
    order: Order,
    amountMinor: Long,
): SettlementRecord =
    SettlementRecord(
        orderId = order.id,
        settledAmount = Money(MoneyMinor.create(amountMinor).shouldBeRight(), JPY),
        settledAt = Instant.parse("2026-08-01T00:00:00Z"),
        providerTransactionId = "txn-1",
    )

/**
 * ReconcileSettlementRecord (ユースケース) を通して、domain の reconcile が返しうる
 * 4つの ReconciliationOutcome 全てに到達できることと、注文が見つからない場合の
 * エラーマッピング (OrderError -> SettlementError)、TxRunner 経由での保存を検証する。
 */
class ReconcileSettlementServiceTest {
    private val fixedClock = Clock.fixed(Instant.parse("2026-08-05T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `金額が一致すれば Matched になり結果が記録される`() =
        runTest {
            val order = orderWith(OrderStatus.PendingPayment, unitPriceMinor = 1_000)
            val orderRepository = FakeOrderRepository(order)
            val settlementRepository = FakeSettlementRepository()
            val txRunner = RecordingTxRunner()
            val service = ReconcileSettlementService(orderRepository, settlementRepository, txRunner, fixedClock)

            val record = recordFor(order, amountMinor = 1_000)
            val outcome = service.invoke(record).shouldBeRight()

            assertEquals(ReconciliationOutcome.Matched, outcome)
            assertEquals(1, settlementRepository.recorded.size)
            assertEquals(fixedClock.instant(), settlementRepository.recorded.single().recordedAt)
        }

    @Test
    fun `金額が食い違えば AmountMismatch になる`() =
        runTest {
            val order = orderWith(OrderStatus.PendingPayment, unitPriceMinor = 1_000)
            val orderRepository = FakeOrderRepository(order)
            val settlementRepository = FakeSettlementRepository()
            val txRunner = RecordingTxRunner()
            val service = ReconcileSettlementService(orderRepository, settlementRepository, txRunner, fixedClock)

            val record = recordFor(order, amountMinor = 999)
            val outcome = service.invoke(record).shouldBeRight()

            assertInstanceOf(ReconciliationOutcome.AmountMismatch::class.java, outcome)
        }

    @Test
    fun `Draft の注文は OrderNotSettleable になる`() =
        runTest {
            val order = orderWith(OrderStatus.Draft)
            val orderRepository = FakeOrderRepository(order)
            val settlementRepository = FakeSettlementRepository()
            val txRunner = RecordingTxRunner()
            val service = ReconcileSettlementService(orderRepository, settlementRepository, txRunner, fixedClock)

            val outcome = service.invoke(recordFor(order, 1_000)).shouldBeRight()

            assertInstanceOf(ReconciliationOutcome.OrderNotSettleable::class.java, outcome)
        }

    @Test
    fun `Paid 以降の注文は AlreadySettled になる`() =
        runTest {
            val order = orderWith(OrderStatus.Paid(Instant.now()))
            val orderRepository = FakeOrderRepository(order)
            val settlementRepository = FakeSettlementRepository()
            val txRunner = RecordingTxRunner()
            val service = ReconcileSettlementService(orderRepository, settlementRepository, txRunner, fixedClock)

            val outcome = service.invoke(recordFor(order, 1_000)).shouldBeRight()

            assertEquals(ReconciliationOutcome.AlreadySettled, outcome)
        }

    @Test
    fun `注文が見つからなければ SettlementError_UnknownOrder に変換され結果は記録されない`() =
        runTest {
            val orderRepository = FakeOrderRepository(initial = null)
            val settlementRepository = FakeSettlementRepository()
            val txRunner = RecordingTxRunner()
            val service = ReconcileSettlementService(orderRepository, settlementRepository, txRunner, fixedClock)

            val missingOrderId = OrderId.create("missing").shouldBeRight()
            val record =
                SettlementRecord(
                    orderId = missingOrderId,
                    settledAmount = Money(MoneyMinor.create(1_000).shouldBeRight(), JPY),
                    settledAt = Instant.now(),
                    providerTransactionId = "txn-1",
                )

            val error = service.invoke(record).shouldBeLeftOfType<SettlementError.UnknownOrder>()

            assertEquals(missingOrderId, error.orderId)
            assertTrue(settlementRepository.recorded.isEmpty())
            assertEquals(true, txRunner.lastResultWasLeft)
        }

    @Test
    fun `リポジトリのインフラ障害は SettlementError_InfrastructureFailure に変換される`() =
        runTest {
            val orderRepository =
                object : com.example.template.application.port.OrderRepository {
                    override suspend fun findById(id: OrderId) = Either.Left(OrderError.RepositoryUnavailable("db down"))

                    override suspend fun findPage(
                        after: OrderId?,
                        limit: Int,
                    ): Either<OrderError, List<Order>> = Either.Left(OrderError.RepositoryUnavailable("db down"))

                    override suspend fun save(order: Order) = Either.Right(order)
                }
            val settlementRepository = FakeSettlementRepository()
            val txRunner = RecordingTxRunner()
            val service = ReconcileSettlementService(orderRepository, settlementRepository, txRunner, fixedClock)

            val order = orderWith(OrderStatus.PendingPayment)
            val error = service.invoke(recordFor(order, 1_000)).shouldBeLeftOfType<SettlementError.InfrastructureFailure>()

            assertEquals("db down", error.cause)
        }
}
