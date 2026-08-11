package com.example.template.bootstrap.paymentgateway

import arrow.core.Either
import com.example.template.application.port.PaymentCharge
import com.example.template.application.port.PaymentGatewayPort
import com.example.template.application.port.PaymentIdempotencyKey
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 実在の決済プロバイダを持たないこのテンプレートのための、常に成功する [PaymentGatewayPort] スタブ。
 *
 * このテンプレートが実演したいのは「決済ゲートウェイ連携のポート設計パターン」そのもの
 * ([PaymentGatewayPort] の KDoc 参照) であり、特定の決済プロバイダの SDK 統合ではない。
 * 実在のプロバイダ (Stripe, GMO PG 等) は契約や API 仕様がまちまちで、テンプレートとして
 * 汎用的に選びようがないため、composition root である :bootstrap は代わりにこの最小限の
 * スタブを組み立てて見せる。テンプレートを実プロジェクトへ転用する際は、このファイルを
 * 実際の決済プロバイダの SDK 呼び出しに差し替えるのが最初の一歩になる
 * (config/PaymentGatewayBeans.kt がこのスタブを [com.example.template.adapter.messaging.resilience.ResilientPaymentGatewayAdapter]
 * (arrow-resilience によるリトライ/サーキットブレーカーのデコレータ) で包んで
 * `PaymentGatewayPort` として公開している箇所を参照)。
 */
class StubPaymentGatewayAdapter(
    private val clock: Clock = Clock.systemUTC(),
) : PaymentGatewayPort {
    private val charges = ConcurrentHashMap<PaymentIdempotencyKey, PaymentCharge>()

    override suspend fun charge(
        orderId: OrderId,
        amount: Money,
        idempotencyKey: PaymentIdempotencyKey,
    ): Either<OrderError, PaymentCharge> =
        Either.Right(
            charges.computeIfAbsent(idempotencyKey) {
                PaymentCharge(paidAt = clock.instant(), providerTransactionId = "stub-${UUID.randomUUID()}")
            },
        )
}
