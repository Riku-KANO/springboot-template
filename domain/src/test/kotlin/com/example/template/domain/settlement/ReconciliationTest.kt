package com.example.template.domain.settlement

import arrow.core.nonEmptyListOf
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")

private val FIXED_ADDRESS =
    ShippingAddress(
        recipientName = "Taro",
        postalCode = "100-0001",
        prefecture = "Tokyo",
        city = "Chiyoda",
        addressLine1 = "1-1-1",
    )

/** [reconcile] が取りうる4つの ReconciliationOutcome すべてに到達可能であることを検証する。 */
class ReconciliationTest {
    private fun orderWith(
        status: OrderStatus,
        unitPriceMinor: Long = 1_000,
    ): Order {
        val line = OrderLine.createFailFast("SKU-1", 1, unitPriceMinor, JPY).shouldBeRight()
        return Order(
            id = OrderId.create("order-1").shouldBeRight(),
            customerId = CustomerId.create("customer-1").shouldBeRight(),
            lines = nonEmptyListOf(line),
            address = FIXED_ADDRESS,
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
            settledAt = Instant.now(),
            providerTransactionId = "txn-1",
        )

    @Test
    fun `PendingPayment かつ金額が一致すれば Matched になる`() {
        val order = orderWith(OrderStatus.PendingPayment, unitPriceMinor = 1_000)
        val record = recordFor(order, amountMinor = 1_000)

        val outcome = reconcile(order, record).shouldBeRight()

        assertEquals(ReconciliationOutcome.Matched, outcome)
    }

    @Test
    fun `PendingPayment だが金額が食い違えば AmountMismatch になる`() {
        val order = orderWith(OrderStatus.PendingPayment, unitPriceMinor = 1_000)
        val record = recordFor(order, amountMinor = 999)

        val outcome = reconcile(order, record).shouldBeRight()

        val mismatch = assertInstanceOf(ReconciliationOutcome.AmountMismatch::class.java, outcome)
        assertEquals(1_000L, mismatch.expected.amount.value)
        assertEquals(999L, mismatch.actual.amount.value)
    }

    @Test
    fun `Draft や Cancelled は OrderNotSettleable になる`() {
        val draft = orderWith(OrderStatus.Draft)
        val draftOutcome =
            assertInstanceOf(
                ReconciliationOutcome.OrderNotSettleable::class.java,
                reconcile(draft, recordFor(draft, 1_000)).shouldBeRight(),
            )
        assertEquals(OrderStatus.Draft, draftOutcome.status)

        val cancelled = orderWith(OrderStatus.Cancelled("reason"))
        val cancelledOutcome =
            assertInstanceOf(
                ReconciliationOutcome.OrderNotSettleable::class.java,
                reconcile(cancelled, recordFor(cancelled, 1_000)).shouldBeRight(),
            )
        assertEquals(OrderStatus.Cancelled("reason"), cancelledOutcome.status)
    }

    @Test
    fun `Paid 以降の状態は既に消込済みとして AlreadySettled になる`() {
        val statuses =
            listOf(
                OrderStatus.Paid(Instant.now()),
                OrderStatus.Fulfilling(Instant.now()),
                OrderStatus.Shipped("TRACK-1"),
                OrderStatus.Delivered(Instant.now()),
                OrderStatus.Refunded(Instant.now(), Money.zero(JPY)),
            )
        statuses.forEach { status ->
            val order = orderWith(status)
            val outcome = reconcile(order, recordFor(order, 1_000)).shouldBeRight()
            assertEquals(ReconciliationOutcome.AlreadySettled, outcome)
        }
    }
}
