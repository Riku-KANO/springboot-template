package com.example.template.adapter.messaging.resilience

import arrow.core.Either
import arrow.resilience.CircuitBreaker
import arrow.resilience.Schedule
import com.example.template.application.port.PaymentCharge
import com.example.template.application.port.PaymentGatewayPort
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Currency
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * arrow-resilience の Schedule (リトライ) / CircuitBreaker の挙動を、決定的な fake の
 * PaymentGatewayPort に対して検証する。kotlinx-coroutines-test の runTest を使うことで、
 * 指数バックオフの実際の delay() 呼び出しは仮想時間上で即座に進み、テストは実時間で
 * スリープしない (「no real sleeping」という要件を満たす)。
 */
class ResilientPaymentGatewayAdapterTest {
    private val orderId = OrderId.create("order-1").shouldBeRight()
    private val jpy: Currency = Currency.getInstance("JPY")
    private val money = Money(MoneyMinor.create(1_000).shouldBeRight(), jpy)

    @Test
    fun `一時的な障害は指数バックオフでリトライされ最終的に成功する`() =
        runTest {
            val attempts = AtomicInteger(0)
            val flakyThenOk =
                PaymentGatewayPort { _, _ ->
                    if (attempts.incrementAndGet() < 3) {
                        Either.Left(OrderError.PaymentGatewayUnavailable("temporarily down"))
                    } else {
                        Either.Right(PaymentCharge(Instant.now(), "txn-ok"))
                    }
                }
            val adapter = ResilientPaymentGatewayAdapter(flakyThenOk)

            val result = adapter.charge(orderId, money).shouldBeRight()

            assertEquals("txn-ok", result.providerTransactionId)
            assertEquals(3, attempts.get())
        }

    @Test
    fun `リトライ上限を超えて失敗し続けたら最後の失敗が Left として返る`() =
        runTest {
            val attempts = AtomicInteger(0)
            val alwaysFailing =
                PaymentGatewayPort { _, _ ->
                    attempts.incrementAndGet()
                    Either.Left(OrderError.PaymentGatewayUnavailable("permanently down"))
                }
            val adapter = ResilientPaymentGatewayAdapter(alwaysFailing)

            val error = adapter.charge(orderId, money).shouldBeLeftOfType<OrderError.PaymentGatewayUnavailable>()

            assertEquals("permanently down", error.cause)
            // Schedule.recurs(3) は「初回 + 最大3回の追加リトライ」なので最大4回まで呼ばれる。
            assertEquals(4, attempts.get())
        }

    @Test
    fun `業務エラー (InvalidTransition) はリトライ対象外なので1回しか呼ばれない`() =
        runTest {
            val attempts = AtomicInteger(0)
            val businessError =
                PaymentGatewayPort { _, _ ->
                    attempts.incrementAndGet()
                    Either.Left(OrderError.InvalidTransition(from = OrderStatus.Draft, attempted = "charge"))
                }
            val adapter = ResilientPaymentGatewayAdapter(businessError)

            adapter.charge(orderId, money).shouldBeLeftOfType<OrderError.InvalidTransition>()

            assertEquals(1, attempts.get())
        }

    @Test
    fun `サーキットブレーカーが開いたら以降の呼び出しは delegate に到達せず即座に拒否される`() =
        runTest {
            val attempts = AtomicInteger(0)
            val alwaysFailing =
                PaymentGatewayPort { _, _ ->
                    attempts.incrementAndGet()
                    Either.Left(OrderError.PaymentGatewayUnavailable("down"))
                }
            // リトライは無効化 (recurs(0) = 初回のみ) して、サーキットブレーカー単体の挙動を分離して検証する。
            // Count.shouldOpen は failuresCount > maxFailures で判定される (= maxFailures は
            // 許容する失敗回数) ため、maxFailures = 0 を指定すると1回目の失敗で即座に Open になる。
            val adapter =
                ResilientPaymentGatewayAdapter(
                    delegate = alwaysFailing,
                    retrySchedule = Schedule.recurs<OrderError>(0),
                    circuitBreaker =
                        CircuitBreaker(
                            resetTimeout = 30.seconds,
                            openingStrategy = CircuitBreaker.OpeningStrategy.Count(maxFailures = 0),
                        ),
                )

            adapter.charge(orderId, money) // 1回目: delegate が失敗しサーキットが開く
            val attemptsAfterFirstCall = attempts.get()

            val secondError = adapter.charge(orderId, money).shouldBeLeftOfType<OrderError.PaymentGatewayUnavailable>()

            assertEquals(attemptsAfterFirstCall, attempts.get(), "circuit が開いている間は delegate が呼ばれてはならない")
            assertEquals(true, secondError.cause.contains("circuit breaker"))
        }
}
