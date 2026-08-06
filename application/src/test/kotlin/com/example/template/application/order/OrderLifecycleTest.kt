package com.example.template.application.order

import arrow.core.Either
import arrow.core.NonEmptyList
import com.example.template.application.port.PaymentCharge
import com.example.template.application.testsupport.FakeOrderRepository
import com.example.template.application.testsupport.FakePaymentGatewayPort
import com.example.template.application.testsupport.RecordingTxRunner
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Currency

/**
 * 「注文作成から配達完了まで、7つの状態遷移のうち到達可能なもの全てを実際にユースケース経由で
 * 辿り切れるか」を検証する結合テスト。
 *
 * このテストが存在する理由はバグの再発防止そのもの: 本チャンク以前は submitForPayment /
 * startFulfilling / deliver / refund を呼び出すユースケースが1つも無く、`Order.create` が必ず
 * Draft を返す一方で PayOrder は PendingPayment を、ShipOrder は Fulfilling を要求するため、
 * 新規作成した注文が `POST /orders/{id}/pay` にも `POST /orders/{id}/ship` にも決して
 * 到達できないという「ライフサイクルの断絶」が起きていた。個々のユースケースの単体テストは
 * それぞれ独立した Fake リポジトリで前提状態を直接 `copy()` して作ってしまうため、
 * この「前段のユースケースが実在の後続状態を作れるか」という繋がりそのものは検証できない。
 * このテストだけが唯一、全ユースケースを1つの FakeOrderRepository を共有させて直列に
 * 呼び出すことで、繋がりの欠落を再現・検出できる。
 */
class OrderLifecycleTest {
    private val jpy: Currency = Currency.getInstance("JPY")

    private val address =
        ShippingAddress(
            recipientName = "Taro Yamada",
            postalCode = "100-0001",
            prefecture = "Tokyo",
            city = "Chiyoda",
            addressLine1 = "1-1-1 Marunouchi",
        )

    @Test
    fun `create から submit, pay, start-fulfillment, ship, deliver まで一気通貫で成功する`() =
        runTest {
            val repository = FakeOrderRepository()
            val txRunner = RecordingTxRunner()
            val paidAt = Instant.parse("2026-08-01T00:00:00Z")
            val gateway = FakePaymentGatewayPort(Either.Right(PaymentCharge(paidAt, "txn-1")))
            val clock = Clock.fixed(Instant.parse("2026-08-02T00:00:00Z"), ZoneOffset.UTC)

            val createOrder = CreateOrderService(repository)
            val submitOrderForPayment = SubmitOrderForPaymentService(repository, txRunner)
            val payOrder = PayOrderService(repository, gateway, txRunner)
            val startFulfillment = StartFulfillmentService(repository, txRunner, clock)
            val shipOrder = ShipOrderService(repository, txRunner)
            val deliverOrder = DeliverOrderService(repository, txRunner, clock)

            val command =
                CreateOrderCommand
                    .create(
                        rawOrderId = "order-1",
                        rawCustomerId = "customer-1",
                        rawLines = NonEmptyList(RawOrderLine("SKU-1", 2, 500), emptyList()),
                        currency = jpy,
                        address = address,
                    ).shouldBeRight()
            val orderId = OrderId.create("order-1").shouldBeRight()

            val created = createOrder(command).shouldBeRight()
            assertEquals(OrderStatus.Draft, created.status)

            val submitted = submitOrderForPayment(SubmitOrderForPaymentCommand(orderId)).shouldBeRight()
            assertEquals(OrderStatus.PendingPayment, submitted.status)

            val paid = payOrder(PayOrderCommand(orderId)).shouldBeRight()
            assertEquals(OrderStatus.Paid(paidAt), paid.status)

            val fulfilling = startFulfillment(StartFulfillmentCommand(orderId)).shouldBeRight()
            assertEquals(OrderStatus.Fulfilling(clock.instant()), fulfilling.status)

            val shipped = shipOrder(ShipOrderCommand(orderId, "TRACK-1")).shouldBeRight()
            assertEquals(OrderStatus.Shipped("TRACK-1"), shipped.status)

            val delivered = deliverOrder(DeliverOrderCommand(orderId)).shouldBeRight()
            assertEquals(OrderStatus.Delivered(clock.instant()), delivered.status)

            // 各ステップの save が実際に永続化 (フェイク上) されており、最終状態が確定していることを確認する。
            assertEquals(OrderStatus.Delivered(clock.instant()), repository.savedOrders.last().status)
        }
}
