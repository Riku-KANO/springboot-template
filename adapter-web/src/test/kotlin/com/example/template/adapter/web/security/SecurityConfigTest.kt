package com.example.template.adapter.web.security

import arrow.core.left
import com.example.template.adapter.web.order.OrderController
import com.example.template.adapter.web.problem.DomainErrorProblemMapper
import com.example.template.application.order.CancelOrder
import com.example.template.application.order.CreateOrder
import com.example.template.application.order.DeliverOrder
import com.example.template.application.order.FindOrder
import com.example.template.application.order.PayOrder
import com.example.template.application.order.RefundOrder
import com.example.template.application.order.ShipOrder
import com.example.template.application.order.StartFulfillment
import com.example.template.application.order.SubmitOrderForPayment
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.OrderId
import com.example.template.domain.testfixtures.shouldBeRight
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.mockJwt
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers.springSecurity
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * SecurityConfig が実際に効いていることを検証する。OrderControllerTest とは異なり、
 * ここでは Security 自動設定を除外せず [SecurityConfig] を明示的に読み込む。
 *
 * `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` にダミーの URI を設定して
 * いるが、`NimbusReactiveJwtDecoder.withJwkSetUri(...)` は Bean 生成時点では JWKS を
 * フェッチしない (実際にトークンを検証する際に遅延フェッチする) ため、このダミー URI に
 * 実際に疎通できなくてもコンテキストは正常に起動する (SecurityConfig.kt のコメント参照)。
 *
 * `mockJwt()` (a `MockServerConfigurer` でもある) はサーバー構築時に専用の WebFilter を
 * 差し込むことでトークン検証自体をバイパスする。`@WebFluxTest` が自動配線する
 * `WebTestClient` は既に構築済みのため、そこへ後から `.mutateWith(mockJwt())` するだけでは
 * この WebFilter が差し込まれない (`MockServerConfigurer` 側のフックはサーバー構築時にしか
 * 効かない)。そのため `WebTestClient.bindToApplicationContext(...).apply(springSecurity())`
 * で自前にクライアントを組み立て、`mockJwt()` が機能する土台を用意している。
 */
@WebFluxTest(controllers = [OrderController::class])
@Import(SecurityConfig::class, DomainErrorProblemMapper::class)
@TestPropertySource(
    properties = ["spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.invalid/jwks.json"],
)
class SecurityConfigTest {
    @Autowired
    lateinit var applicationContext: ApplicationContext

    @Autowired
    lateinit var findOrder: FindOrder

    private lateinit var webTestClient: WebTestClient

    @BeforeEach
    fun setUp() {
        webTestClient = WebTestClient.bindToApplicationContext(applicationContext).apply(springSecurity()).build()
    }

    @TestConfiguration
    class MockUseCases {
        @Bean
        fun createOrder(): CreateOrder = mockk()

        @Bean
        fun findOrder(): FindOrder = mockk()

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

    @Test
    fun `unauthenticated request is rejected with 401`() {
        webTestClient
            .get()
            .uri("/orders/order-1")
            .exchange()
            .expectStatus()
            .isUnauthorized
    }

    @Test
    fun `request with a valid JWT is authenticated and reaches the controller`() {
        val orderId = OrderId.create("order-1").shouldBeRight()
        coEvery { findOrder(any()) } returns OrderError.OrderNotFound(orderId).left()

        webTestClient
            .mutateWith(mockJwt())
            .get()
            .uri("/orders/order-1")
            .exchange()
            // 401/403 で弾かれていない (認証・認可は通過した) ことが本質。
            // モックが OrderNotFound を返すよう設定してあるので 404 まで到達する。
            .expectStatus()
            .isNotFound
    }

    @Test
    fun `actuator health endpoint is open without authentication`() {
        webTestClient
            .get()
            .uri("/actuator/health")
            .exchange()
            .expectStatus()
            // actuator 自体はこのモジュールの依存に含まれていないため実際のエンドポイントは
            // 存在しないが (404)、それでも 401/403 にはならないことが permitAll の証明になる。
            .isNotFound
    }
}
