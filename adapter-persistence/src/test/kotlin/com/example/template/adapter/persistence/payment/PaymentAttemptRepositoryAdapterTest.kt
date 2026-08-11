package com.example.template.adapter.persistence.payment

import arrow.core.NonEmptyList
import com.example.template.adapter.persistence.order.OrderRepositoryAdapter
import com.example.template.adapter.persistence.testsupport.PostgresIntegrationTest
import com.example.template.application.port.PaymentAttemptStatus
import com.example.template.application.port.PaymentCharge
import com.example.template.application.port.PaymentIdempotencyKey
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
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Currency
import java.util.UUID

class PaymentAttemptRepositoryAdapterTest : PostgresIntegrationTest() {
    private val orders by lazy { OrderRepositoryAdapter(databaseClient, transactionalOperator) }
    private val attempts by lazy { PaymentAttemptRepositoryAdapter(databaseClient) }
    private val jpy = Currency.getInstance("JPY")

    private suspend fun persistOrder(): OrderId {
        val orderId = OrderId.create("order-${UUID.randomUUID()}").shouldBeRight()
        orders
            .save(
                Order(
                    id = orderId,
                    customerId = CustomerId.create("customer-1").shouldBeRight(),
                    lines = NonEmptyList(OrderLine.createFailFast("SKU-1", 1, 1_000, jpy).shouldBeRight(), emptyList()),
                    address = ShippingAddress("Name", "100-0001", "Tokyo", "Chiyoda", "1-1"),
                    status = OrderStatus.PendingPayment,
                ),
            ).shouldBeRight()
        return orderId
    }

    @Test
    fun `create and success transitions are idempotent`() =
        runTest {
            val orderId = persistOrder()
            val key = PaymentIdempotencyKey.forOrder(orderId)
            val amount = Money(MoneyMinor.create(1_000).shouldBeRight(), jpy)
            val charge = PaymentCharge(Instant.parse("2026-08-01T00:00:00Z"), "txn-${UUID.randomUUID()}")

            val first = attempts.findOrCreate(orderId, key, amount).shouldBeRight()
            val second = attempts.findOrCreate(orderId, key, amount).shouldBeRight()
            assertEquals(PaymentAttemptStatus.Pending, first.status)
            assertEquals(first, second)

            val succeeded = attempts.markSucceeded(key, charge).shouldBeRight()
            val replay = attempts.markSucceeded(key, charge).shouldBeRight()
            assertEquals(PaymentAttemptStatus.Succeeded(charge), succeeded.status)
            assertEquals(succeeded, replay)
        }

    @Test
    fun `same key cannot be reused with a different amount`() =
        runTest {
            val orderId = persistOrder()
            val key = PaymentIdempotencyKey.forOrder(orderId)
            attempts
                .findOrCreate(orderId, key, Money(MoneyMinor.create(1_000).shouldBeRight(), jpy))
                .shouldBeRight()

            attempts
                .findOrCreate(orderId, key, Money(MoneyMinor.create(2_000).shouldBeRight(), jpy))
                .shouldBeLeftOfType<OrderError.RepositoryUnavailable>()
        }
}
