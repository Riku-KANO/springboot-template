package com.example.template.domain.order

import arrow.core.nonEmptyListOf
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.orderLine
import com.example.template.domain.testfixtures.shippingAddress
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import io.kotest.property.Arb
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")

/**
 * OrderStatus の状態機械を検証する。
 * 「すべての正しい遷移が成功する」ことと「代表的な不正遷移が InvalidTransition を返す」
 * ことの両方を見る。全 56 通り (7遷移関数 x 8状態) を総当たりはしないが、
 * 各遷移関数について「唯一の正しい遷移元」と「それ以外の代表例」をカバーする。
 */
class OrderTransitionsTest {
    private fun draftOrder(
        line: OrderLine,
        address: ShippingAddress,
    ) = Order(
        id = OrderId.create("order-1").shouldBeRight(),
        customerId = CustomerId.create("customer-1").shouldBeRight(),
        lines = nonEmptyListOf(line),
        address = address,
        status = OrderStatus.Draft,
    )

    @Test
    fun `submitForPayment は Draft からのみ成功する`() =
        runTest {
            checkAll(Arb.orderLine(), Arb.shippingAddress()) { line, address ->
                val order = draftOrder(line, address)
                val next = order.submitForPayment().shouldBeRight()
                assertEquals(OrderStatus.PendingPayment, next.status)

                // Draft 以外のあらゆる状態からは失敗する (代表例として遷移後の状態で再実行)
                next.submitForPayment().shouldBeLeftOfType<OrderError.InvalidTransition>()
            }
        }

    @Test
    fun `submitPayment は PendingPayment からのみ成功する`() =
        runTest {
            checkAll(Arb.orderLine(), Arb.shippingAddress()) { line, address ->
                val pending = draftOrder(line, address).copy(status = OrderStatus.PendingPayment)
                val paidAt = Instant.now()
                val next = pending.submitPayment(paidAt).shouldBeRight()
                assertTrue(next.status is OrderStatus.Paid)
                assertEquals(paidAt, (next.status as OrderStatus.Paid).paidAt)

                // Draft からは失敗する
                draftOrder(line, address).submitPayment(paidAt).shouldBeLeftOfType<OrderError.InvalidTransition>()
            }
        }

    @Test
    fun `startFulfilling は Paid からのみ成功する`() =
        runTest {
            checkAll(Arb.orderLine(), Arb.shippingAddress()) { line, address ->
                val paid = draftOrder(line, address).copy(status = OrderStatus.Paid(Instant.now()))
                val startedAt = Instant.now()
                val next = paid.startFulfilling(startedAt).shouldBeRight()
                assertTrue(next.status is OrderStatus.Fulfilling)

                draftOrder(line, address).startFulfilling(startedAt).shouldBeLeftOfType<OrderError.InvalidTransition>()
            }
        }

    @Test
    fun `ship は Fulfilling からのみ成功する`() =
        runTest {
            checkAll(Arb.orderLine(), Arb.shippingAddress()) { line, address ->
                val fulfilling = draftOrder(line, address).copy(status = OrderStatus.Fulfilling(Instant.now()))
                val next = fulfilling.ship("TRACK-123").shouldBeRight()
                assertEquals(OrderStatus.Shipped("TRACK-123"), next.status)

                draftOrder(line, address).ship("TRACK-123").shouldBeLeftOfType<OrderError.InvalidTransition>()
            }
        }

    @Test
    fun `deliver は Shipped からのみ成功する`() =
        runTest {
            checkAll(Arb.orderLine(), Arb.shippingAddress()) { line, address ->
                val shipped = draftOrder(line, address).copy(status = OrderStatus.Shipped("TRACK-123"))
                val deliveredAt = Instant.now()
                val next = shipped.deliver(deliveredAt).shouldBeRight()
                assertEquals(OrderStatus.Delivered(deliveredAt), next.status)

                draftOrder(line, address).deliver(deliveredAt).shouldBeLeftOfType<OrderError.InvalidTransition>()
            }
        }

    @Test
    fun `cancel は Draft, PendingPayment, Paid からは成功し Fulfilling 以降からは失敗する`() =
        runTest {
            checkAll(Arb.orderLine(), Arb.shippingAddress()) { line, address ->
                val cancellable =
                    listOf(
                        OrderStatus.Draft,
                        OrderStatus.PendingPayment,
                        OrderStatus.Paid(Instant.now()),
                    )
                cancellable.forEach { status ->
                    val order = draftOrder(line, address).copy(status = status)
                    val next = order.cancel("customer request").shouldBeRight()
                    assertEquals(OrderStatus.Cancelled("customer request"), next.status)
                }

                val notCancellable =
                    listOf(
                        OrderStatus.Fulfilling(Instant.now()),
                        OrderStatus.Shipped("TRACK-123"),
                        OrderStatus.Delivered(Instant.now()),
                        OrderStatus.Cancelled("already"),
                        OrderStatus.Refunded(Instant.now(), Money.zero(JPY)),
                    )
                notCancellable.forEach { status ->
                    val order = draftOrder(line, address).copy(status = status)
                    order.cancel("customer request").shouldBeLeftOfType<OrderError.InvalidTransition>()
                }
            }
        }

    @Test
    fun `refund は Paid, Delivered からは成功しそれ以外からは失敗する`() =
        runTest {
            checkAll(Arb.orderLine(), Arb.shippingAddress()) { line, address ->
                val refundedAt = Instant.now()
                val amount = Money.zero(JPY)

                val refundable =
                    listOf(
                        OrderStatus.Paid(Instant.now()),
                        OrderStatus.Delivered(Instant.now()),
                    )
                refundable.forEach { status ->
                    val order = draftOrder(line, address).copy(status = status)
                    val next = order.refund(refundedAt, amount).shouldBeRight()
                    assertEquals(OrderStatus.Refunded(refundedAt, amount), next.status)
                }

                val notRefundable =
                    listOf(
                        OrderStatus.Draft,
                        OrderStatus.PendingPayment,
                        OrderStatus.Fulfilling(Instant.now()),
                        OrderStatus.Shipped("TRACK-123"),
                        OrderStatus.Cancelled("reason"),
                        OrderStatus.Refunded(Instant.now(), amount),
                    )
                notRefundable.forEach { status ->
                    val order = draftOrder(line, address).copy(status = status)
                    order.refund(refundedAt, amount).shouldBeLeftOfType<OrderError.InvalidTransition>()
                }
            }
        }
}
