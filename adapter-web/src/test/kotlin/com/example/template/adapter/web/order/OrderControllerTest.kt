package com.example.template.adapter.web.order

import arrow.core.NonEmptyList
import arrow.core.left
import arrow.core.right
import com.example.template.adapter.web.problem.DomainErrorProblemMapper
import com.example.template.application.order.CancelOrder
import com.example.template.application.order.CreateOrder
import com.example.template.application.order.DeliverOrder
import com.example.template.application.order.FindOrder
import com.example.template.application.order.ListOrders
import com.example.template.application.order.OrderPage
import com.example.template.application.order.PayOrder
import com.example.template.application.order.RefundOrder
import com.example.template.application.order.ShipOrder
import com.example.template.application.order.StartFulfillment
import com.example.template.application.order.SubmitOrderForPayment
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.Quantity
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.shared.Sku
import com.example.template.domain.testfixtures.shouldBeRight
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.security.autoconfigure.ReactiveUserDetailsServiceAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.reactive.ReactiveWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.reactive.ReactiveOAuth2ResourceServerAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.reactive.ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Instant
import java.util.Currency

/**
 * OrderController のスライステスト。Security は明示的に自動設定から除外し (別途
 * SecurityConfigTest で検証する)、ユースケースは MockK でモック化して振る舞いを固定する。
 * ヘッドライン機能である「累積バリデーション」が実際の HTTP レスポンスとして
 * 3件同時に返ることを検証するのが最重要のテストケース。
 */
@WebFluxTest(
    controllers = [OrderController::class],
    excludeAutoConfiguration = [
        ReactiveWebSecurityAutoConfiguration::class,
        ReactiveUserDetailsServiceAutoConfiguration::class,
        ReactiveOAuth2ResourceServerAutoConfiguration::class,
        ReactiveOAuth2ResourceServerWebSecurityAutoConfiguration::class,
    ],
)
@Import(DomainErrorProblemMapper::class)
class OrderControllerTest {
    @Autowired
    lateinit var webTestClient: WebTestClient

    @Autowired
    lateinit var createOrder: CreateOrder

    @Autowired
    lateinit var findOrder: FindOrder

    @Autowired
    lateinit var listOrders: ListOrders

    @Autowired
    lateinit var submitOrderForPayment: SubmitOrderForPayment

    @Autowired
    lateinit var payOrder: PayOrder

    @Autowired
    lateinit var startFulfillment: StartFulfillment

    @Autowired
    lateinit var shipOrder: ShipOrder

    @Autowired
    lateinit var deliverOrder: DeliverOrder

    @Autowired
    lateinit var cancelOrder: CancelOrder

    @Autowired
    lateinit var refundOrder: RefundOrder

    @TestConfiguration
    class MockUseCases {
        @Bean
        fun createOrder(): CreateOrder = mockk()

        @Bean
        fun findOrder(): FindOrder = mockk()

        @Bean
        fun listOrders(): ListOrders = mockk()

        @Bean
        fun submitOrderForPayment(): SubmitOrderForPayment = mockk()

        @Bean
        fun payOrder(): PayOrder = mockk()

        @Bean
        fun startFulfillment(): StartFulfillment = mockk()

        @Bean
        fun shipOrder(): ShipOrder = mockk()

        @Bean
        fun deliverOrder(): DeliverOrder = mockk()

        @Bean
        fun cancelOrder(): CancelOrder = mockk()

        @Bean
        fun refundOrder(): RefundOrder = mockk()
    }

    private fun sampleOrder(): Order {
        val id = OrderId.create("order-1").shouldBeRight()
        val customerId = CustomerId.create("customer-1").shouldBeRight()
        val currency = Currency.getInstance("JPY")
        val sku = Sku.create("SKU-1").shouldBeRight()
        val quantity = Quantity.create(2).shouldBeRight()
        val price = Money(MoneyMinor.create(500).shouldBeRight(), currency)
        val address = ShippingAddress("Taro Yamada", "100-0001", "Tokyo", "Chiyoda", "1-1-1")
        return Order(id, customerId, NonEmptyList(OrderLine(sku, quantity, price), emptyList()), address, OrderStatus.Draft)
    }

    private fun createOrderRequestJson(
        orderId: String = "order-1",
        customerId: String = "customer-1",
        sku: String = "SKU-1",
    ): String =
        """
        {
          "orderId": "$orderId",
          "customerId": "$customerId",
          "currency": "JPY",
          "lines": [{"sku": "$sku", "quantity": 2, "unitPriceMinor": 500}],
          "address": {
            "recipientName": "Taro Yamada",
            "postalCode": "100-0001",
            "prefecture": "Tokyo",
            "city": "Chiyoda",
            "addressLine1": "1-1-1"
          }
        }
        """.trimIndent()

    @Test
    fun `create returns 201 with mapped response on success`() {
        coEvery { createOrder(any()) } returns sampleOrder().right()

        webTestClient
            .post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(createOrderRequestJson())
            .exchange()
            .expectStatus()
            .isCreated
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo("order-1")
            .jsonPath("$.status")
            .isEqualTo("DRAFT")
    }

    @Test
    fun `create with 3 simultaneously invalid fields returns all 3 errors in one problem+json response`() {
        // orderId: 空文字 (BlankOrderId), customerId: 空文字 (BlankCustomerId),
        // sku: 不正フォーマット (InvalidSkuFormat) の3つを同時に不正にする。
        // CreateOrderCommand.create の EitherNel<ValidationError, _> がこれを1回で
        // まとめて返すことをここで実証する。createOrder ユースケース自体は
        // コマンド構築の時点で失敗するため一度も呼ばれない。
        val body = createOrderRequestJson(orderId = "", customerId = "", sku = "invalid sku!!")

        webTestClient
            .post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.errors.length()")
            .isEqualTo(3)
    }

    @Test
    fun `create with empty lines is rejected as a structural bad request before reaching the domain layer`() {
        val body =
            """
            {
              "orderId": "order-1",
              "customerId": "customer-1",
              "currency": "JPY",
              "lines": [],
              "address": {
                "recipientName": "Taro Yamada",
                "postalCode": "100-0001",
                "prefecture": "Tokyo",
                "city": "Chiyoda",
                "addressLine1": "1-1-1"
              }
            }
            """.trimIndent()

        webTestClient
            .post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `get returns 404 problem+json when order is not found`() {
        val orderId = OrderId.create("missing-order").shouldBeRight()
        coEvery { findOrder(any()) } returns OrderError.OrderNotFound(orderId).left()

        webTestClient
            .get()
            .uri("/orders/missing-order")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo(404)
            .jsonPath("$.code")
            .isEqualTo("ORDER_NOT_FOUND")
    }

    @Test
    fun `Accept-Language Japanese localizes problem details without changing stable code`() {
        val orderId = OrderId.create("missing-order").shouldBeRight()
        coEvery { findOrder(any()) } returns OrderError.OrderNotFound(orderId).left()

        webTestClient
            .get()
            .uri("/orders/missing-order")
            .header("Accept-Language", "ja-JP, en;q=0.8")
            .exchange()
            .expectStatus()
            .isNotFound
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("ORDER_NOT_FOUND")
            .jsonPath("$.detail")
            .isEqualTo("注文「missing-order」が見つかりません。")
    }

    @Test
    fun `get returns 200 with mapped order on success`() {
        coEvery { findOrder(any()) } returns sampleOrder().right()

        webTestClient
            .get()
            .uri("/orders/order-1")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.id")
            .isEqualTo("order-1")
    }

    @Test
    fun `list returns items and a stable ID cursor`() {
        val order = sampleOrder()
        coEvery { listOrders(any()) } returns OrderPage(listOf(order), order.id).right()

        webTestClient
            .get()
            .uri("/orders?limit=1")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.items[0].id")
            .isEqualTo("order-1")
            .jsonPath("$.nextCursor")
            .isEqualTo("order-1")
    }

    @Test
    fun `list rejects a limit outside the supported range`() {
        webTestClient
            .get()
            .uri("/orders?limit=101")
            .exchange()
            .expectStatus()
            .isBadRequest
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("PAGE_LIMIT_INVALID")
    }

    @Test
    fun `submit returns 200 with updated status on success`() {
        val submitted = sampleOrder().copy(status = OrderStatus.PendingPayment)
        coEvery { submitOrderForPayment(any()) } returns submitted.right()

        webTestClient
            .post()
            .uri("/orders/order-1/submit")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("PENDING_PAYMENT")
    }

    @Test
    fun `submit returns 409 conflict when the transition is invalid`() {
        coEvery { submitOrderForPayment(any()) } returns
            OrderError.InvalidTransition(OrderStatus.PendingPayment, "submitForPayment").left()

        webTestClient
            .post()
            .uri("/orders/order-1/submit")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `pay returns 409 conflict when the transition is invalid`() {
        coEvery { payOrder(any()) } returns
            OrderError.InvalidTransition(OrderStatus.Draft, "submitPayment").left()

        webTestClient
            .post()
            .uri("/orders/order-1/pay")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `start-fulfillment returns 200 with updated status on success`() {
        val fulfilling = sampleOrder().copy(status = OrderStatus.Fulfilling(Instant.parse("2026-08-01T00:00:00Z")))
        coEvery { startFulfillment(any()) } returns fulfilling.right()

        webTestClient
            .post()
            .uri("/orders/order-1/start-fulfillment")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("FULFILLING")
    }

    @Test
    fun `ship returns 200 with updated status on success`() {
        val shipped = sampleOrder().copy(status = OrderStatus.Shipped("TRACK-1"))
        coEvery { shipOrder(any()) } returns shipped.right()

        webTestClient
            .post()
            .uri("/orders/order-1/ship")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"trackingNumber": "TRACK-1"}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("SHIPPED")
    }

    @Test
    fun `deliver returns 200 with updated status on success`() {
        val delivered = sampleOrder().copy(status = OrderStatus.Delivered(Instant.parse("2026-08-03T00:00:00Z")))
        coEvery { deliverOrder(any()) } returns delivered.right()

        webTestClient
            .post()
            .uri("/orders/order-1/deliver")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("DELIVERED")
    }

    @Test
    fun `deliver returns 409 conflict when the transition is invalid`() {
        coEvery { deliverOrder(any()) } returns
            OrderError.InvalidTransition(OrderStatus.Draft, "deliver").left()

        webTestClient
            .post()
            .uri("/orders/order-1/deliver")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }

    @Test
    fun `cancel returns 503 when infrastructure is unavailable`() {
        coEvery { cancelOrder(any()) } returns OrderError.RepositoryUnavailable("connection refused").left()

        webTestClient
            .post()
            .uri("/orders/order-1/cancel")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"reason": "customer request"}""")
            .exchange()
            .expectStatus()
            .isEqualTo(503)
            .expectBody()
            .jsonPath("$.code")
            .isEqualTo("ORDER_REPOSITORY_UNAVAILABLE")
            .jsonPath("$.detail")
            .value<String> { detail -> assertFalse(detail.contains("connection refused")) }
    }

    @Test
    fun `refund returns 200 with updated status on success`() {
        val amount = Money(MoneyMinor.create(500).shouldBeRight(), Currency.getInstance("JPY"))
        val refunded = sampleOrder().copy(status = OrderStatus.Refunded(Instant.parse("2026-08-04T00:00:00Z"), amount))
        coEvery { refundOrder(any()) } returns refunded.right()

        webTestClient
            .post()
            .uri("/orders/order-1/refund")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"amountMinor": 500, "currency": "JPY"}""")
            .exchange()
            .expectStatus()
            .isOk
            .expectBody()
            .jsonPath("$.status")
            .isEqualTo("REFUNDED")
    }

    @Test
    fun `refund returns 400 bad request for a structurally invalid currency code before reaching the use case`() {
        webTestClient
            .post()
            .uri("/orders/order-1/refund")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"amountMinor": 500, "currency": "not-a-currency"}""")
            .exchange()
            .expectStatus()
            .isBadRequest
    }

    @Test
    fun `refund returns 409 conflict when the transition is invalid`() {
        coEvery { refundOrder(any()) } returns
            OrderError.InvalidTransition(OrderStatus.Draft, "refund").left()

        webTestClient
            .post()
            .uri("/orders/order-1/refund")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"amountMinor": 500, "currency": "JPY"}""")
            .exchange()
            .expectStatus()
            .isEqualTo(409)
            .expectHeader()
            .contentType(MediaType.APPLICATION_PROBLEM_JSON)
    }
}
