package com.example.template.adapter.persistence.order

import arrow.core.NonEmptyList
import com.example.template.adapter.persistence.testsupport.PostgresIntegrationTest
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

/**
 * [OrderRepositoryAdapter] の統合テスト。
 *
 * 主眼は「OrderStatus (8バリアント) の永続化が全パターンで正しくラウンドトリップすること」。
 * ここでの equals は Order / OrderLine / OrderStatus がすべて data class (または value class) で
 * あることに支えられている ── save したオブジェクトと findById で読み直したオブジェクトの
 * 構造的等価性を素直に assertEquals で検証できる。もし OrderStatusMapping.kt の
 * エンコード/デコードのどちらかにバグがあれば (例えばペイロード列を1つ書き忘れる等)、
 * ここでラウンドトリップが崩れて検出される。
 *
 * Instant は意図的に秒単位 (ミリ秒未満の端数なし) の固定値を使っている。Postgres の
 * TIMESTAMPTZ はマイクロ秒精度のため、ナノ秒精度の Instant を使うと丸められて
 * 元の値と一致しなくなる可能性があるが、業務データとして秒単位の精度で十分な値を使えば
 * この懸念は生じない。
 */
class OrderRepositoryAdapterTest : PostgresIntegrationTest() {
    private val repository by lazy { OrderRepositoryAdapter(databaseClient, transactionalOperator) }

    private val jpy: Currency = Currency.getInstance("JPY")

    private fun newOrderId(): OrderId = OrderId.create("order-${UUID.randomUUID()}").shouldBeRight()

    private fun sampleLines(): NonEmptyList<OrderLine> =
        NonEmptyList(
            OrderLine.createFailFast("SKU-1", 2, 1_000, jpy).shouldBeRight(),
            listOf(OrderLine.createFailFast("SKU-2", 1, 500, jpy).shouldBeRight()),
        )

    private fun sampleAddress(): ShippingAddress =
        ShippingAddress(
            recipientName = "Taro Yamada",
            postalCode = "100-0001",
            prefecture = "Tokyo",
            city = "Chiyoda",
            addressLine1 = "1-1 Chiyoda",
            addressLine2 = null,
        )

    private fun newOrder(status: OrderStatus): Order =
        Order(
            id = newOrderId(),
            customerId = CustomerId.create("customer-1").shouldBeRight(),
            lines = sampleLines(),
            address = sampleAddress(),
            status = status,
        )

    private suspend fun assertRoundTrips(order: Order) {
        val saved = repository.save(order).shouldBeRight()
        assertEquals(order, saved)
        val loaded = repository.findById(order.id).shouldBeRight()
        assertEquals(order, loaded)
    }

    @Test
    fun `round trips Draft`() =
        runTest {
            assertRoundTrips(newOrder(OrderStatus.Draft))
        }

    @Test
    fun `round trips PendingPayment`() =
        runTest {
            assertRoundTrips(newOrder(OrderStatus.PendingPayment))
        }

    @Test
    fun `round trips Paid`() =
        runTest {
            assertRoundTrips(newOrder(OrderStatus.Paid(paidAt = Instant.parse("2024-01-15T10:30:00Z"))))
        }

    @Test
    fun `round trips Fulfilling`() =
        runTest {
            assertRoundTrips(newOrder(OrderStatus.Fulfilling(startedAt = Instant.parse("2024-01-16T09:00:00Z"))))
        }

    @Test
    fun `round trips Shipped`() =
        runTest {
            assertRoundTrips(newOrder(OrderStatus.Shipped(trackingNumber = "TRACK-12345")))
        }

    @Test
    fun `round trips Delivered`() =
        runTest {
            assertRoundTrips(newOrder(OrderStatus.Delivered(deliveredAt = Instant.parse("2024-01-20T15:45:00Z"))))
        }

    @Test
    fun `round trips Cancelled`() =
        runTest {
            assertRoundTrips(newOrder(OrderStatus.Cancelled(reason = "customer requested cancellation")))
        }

    @Test
    fun `round trips Refunded`() =
        runTest {
            val amount = Money(MoneyMinor.create(500).shouldBeRight(), jpy)
            assertRoundTrips(newOrder(OrderStatus.Refunded(refundedAt = Instant.parse("2024-01-25T12:00:00Z"), amount = amount)))
        }

    @Test
    fun `findById returns OrderNotFound for an id that was never saved`() =
        runTest {
            val error = repository.findById(newOrderId()).shouldBeLeftOfType<OrderError.OrderNotFound>()
            assertEquals(true, error.message.contains("not found"))
        }

    @Test
    fun `save replaces the full set of order_lines on a subsequent call (upsert semantics)`() =
        runTest {
            val original = newOrder(OrderStatus.Draft)
            repository.save(original).shouldBeRight()

            // 明細を2件 -> 1件に差し替える。差分更新ではなく「全消去してから書き直す」
            // 実装であれば、古い2件目の明細 (SKU-2) が残ってしまうことはない。
            val singleLine =
                NonEmptyList(
                    OrderLine.createFailFast("SKU-ONLY", 3, 700, jpy).shouldBeRight(),
                    emptyList(),
                )
            val updated = original.copy(lines = singleLine)
            repository.save(updated).shouldBeRight()

            val loaded = repository.findById(original.id).shouldBeRight()
            assertEquals(updated, loaded)
            assertEquals(1, loaded.lines.all.size)
        }
}
