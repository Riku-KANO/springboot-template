package com.example.template.adapter.messaging.resilience

import arrow.core.Either
import arrow.core.getOrElse
import arrow.resilience.CircuitBreaker
import arrow.resilience.Schedule
import arrow.resilience.retryEither
import com.example.template.application.port.PaymentCharge
import com.example.template.application.port.PaymentGatewayPort
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * arrow-resilience (Schedule + CircuitBreaker) を使った耐障害性デコレータの実例。
 *
 * このクラスが唯一やっていること: [PaymentGatewayPort] という同じポートをもう一段ラップし、
 * リトライとサーキットブレーカーという「決済ゲートウェイが時々失敗する」という現実に対処するための
 * 横断的関心事を足すだけ。:application 層のユースケース (PayOrderService 等) は
 * ResilientPaymentGatewayAdapter を使っているか、素の実装を直接使っているかを一切区別できない
 * (同じインターフェースを実装しているため)。:bootstrap (Chunk 6) が Bean 定義でどちらを注入するか
 * 選べる、というのがこの「デコレータ」パターンを採用した狙い。
 *
 * 耐障害性 (retry/circuit breaker) をこのアダプタ境界の中に閉じ込めているのは意図的な設計判断。
 * :domain / :application にリトライ回数やタイムアウトの概念を漏らしてしまうと、ユースケースの
 * テストが「何秒でタイムアウトするか」のような実装詳細に依存し始め、ビジネスロジックのテストと
 * インフラの都合が絡み合ってしまう。「決済ゲートウェイは失敗しうる」という事実そのものは
 * [OrderError.PaymentGatewayUnavailable] として :domain 側の語彙に既に存在するので、
 * このアダプタは最終的にその型へ変換して返すだけでよく、上位層に新しい概念を要求しない。
 */
class ResilientPaymentGatewayAdapter(
    private val delegate: PaymentGatewayPort,
    private val retrySchedule: Schedule<OrderError, *> = defaultRetrySchedule(),
    private val circuitBreaker: CircuitBreaker = defaultCircuitBreaker(),
) : PaymentGatewayPort {
    override suspend fun charge(
        orderId: OrderId,
        amount: Money,
    ): Either<OrderError, PaymentCharge> = retrySchedule.retryEither { chargeOnce(orderId, amount) }

    /**
     * サーキットブレーカーで1回分の呼び出しを保護する。
     *
     * [CircuitBreaker] は「例外を投げたか」でしか失敗を判定できない (protectOrThrow は内部で
     * 例外を捕捉して失敗カウントに計上し、再送出する)。一方 [PaymentGatewayPort.charge] は例外を
     * 投げず Either.Left で失敗を表現する。そこでこの関数の中でだけ Left を一時的に例外に変換して
     * サーキットブレーカーに「これは失敗である」と伝え、関数を抜ける前に必ず Either へ戻す。
     * この変換は関数の外に一切漏れない (呼び出し元は例外を意識しない) ため、
     * 「ポート境界を例外が越えてはならない」という方針には違反しない。
     */
    private suspend fun chargeOnce(
        orderId: OrderId,
        amount: Money,
    ): Either<OrderError, PaymentCharge> =
        Either
            .catch {
                circuitBreaker.protectEither {
                    delegate.charge(orderId, amount).getOrElse { error -> throw DelegateChargeFailed(error) }
                }
            }.fold(
                { throwable -> Either.Left(throwable.toOrderError()) },
                { rejectedOrCharge -> rejectedOrCharge.mapLeft { rejected -> rejected.toOrderError() } },
            )

    private fun Throwable.toOrderError(): OrderError =
        when (this) {
            is DelegateChargeFailed -> error
            else -> OrderError.PaymentGatewayUnavailable(message ?: "payment gateway call failed unexpectedly")
        }

    private fun CircuitBreaker.ExecutionRejected.toOrderError(): OrderError =
        OrderError.PaymentGatewayUnavailable("circuit breaker is open, rejecting call: $reason")

    /** [delegate] が返した Either.Left をサーキットブレーカーに失敗として認識させるための内部シグナル。 */
    private class DelegateChargeFailed(
        val error: OrderError,
    ) : RuntimeException(error.message)

    companion object {
        private val RETRY_BASE_DELAY = 100.milliseconds
        private const val MAX_ATTEMPTS = 3L
        private val CIRCUIT_RESET_TIMEOUT = 5.seconds
        private const val MAX_CONSECUTIVE_FAILURES = 3

        /**
         * 指数バックオフ + jitter (同時リトライの衝突=サンダリングハードを避けるためのランダムなブレ幅) で
         * 最大 [MAX_ATTEMPTS] 回リトライする Schedule。[OrderError.PaymentGatewayUnavailable]
         * (インフラ起因、再試行すれば直る可能性がある) だけを対象にし、
         * [OrderError.InvalidTransition] のような業務エラーはリトライしても無意味なので対象外にする
         * (doWhile で明示的に絞り込む)。
         */
        fun defaultRetrySchedule(): Schedule<OrderError, *> =
            Schedule
                .exponential<OrderError>(RETRY_BASE_DELAY)
                .jittered()
                .doWhile { error, _ -> error is OrderError.PaymentGatewayUnavailable }
                .and(Schedule.recurs(MAX_ATTEMPTS))

        /**
         * arrow-resilience の `Count.shouldOpen()` は `failuresCount > maxFailures` で判定するため、
         * `maxFailures` は「許容する失敗回数」を意味する ( = maxFailures+1 回目の失敗で Open になる)。
         * ここでは連続 [MAX_CONSECUTIVE_FAILURES] 回まで許容し、それを超えたら Open (以後の呼び出しを
         * 即座に拒否) にし、[CIRCUIT_RESET_TIMEOUT] 経過後に半開状態から様子見の1回を試す、
         * という標準的な構成にする。
         */
        fun defaultCircuitBreaker(): CircuitBreaker =
            CircuitBreaker(
                resetTimeout = CIRCUIT_RESET_TIMEOUT,
                openingStrategy = CircuitBreaker.OpeningStrategy.Count(maxFailures = MAX_CONSECUTIVE_FAILURES),
            )
    }
}
