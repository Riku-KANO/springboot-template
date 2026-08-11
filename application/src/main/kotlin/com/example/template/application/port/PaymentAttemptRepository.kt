package com.example.template.application.port

import arrow.core.Either
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId

/**
 * 決済プロバイダへ渡す冪等キー。
 * 注文の支払いライフサイクルにつき1つを固定して使い、通信失敗後の再試行でも変更しない。
 */
@JvmInline
value class PaymentIdempotencyKey(
    val value: String,
) {
    companion object {
        fun forOrder(orderId: OrderId): PaymentIdempotencyKey = PaymentIdempotencyKey("order-payment:${orderId.value}")
    }
}

/** 外部決済と注文更新の間をつなぐ、永続的な処理状態。 */
data class PaymentAttempt(
    val orderId: OrderId,
    val idempotencyKey: PaymentIdempotencyKey,
    val amount: Money,
    val status: PaymentAttemptStatus,
)

sealed interface PaymentAttemptStatus {
    data object Pending : PaymentAttemptStatus

    data class Succeeded(
        val charge: PaymentCharge,
    ) : PaymentAttemptStatus
}

/**
 * 外部決済をDBトランザクション内で実行せずに済むよう、処理意図と結果を永続化するポート。
 * 全メソッドは同じ入力に対して冪等でなければならない。
 */
interface PaymentAttemptRepository {
    suspend fun findByOrderId(orderId: OrderId): Either<OrderError, PaymentAttempt?>

    suspend fun findOrCreate(
        orderId: OrderId,
        idempotencyKey: PaymentIdempotencyKey,
        amount: Money,
    ): Either<OrderError, PaymentAttempt>

    suspend fun markSucceeded(
        idempotencyKey: PaymentIdempotencyKey,
        charge: PaymentCharge,
    ): Either<OrderError, PaymentAttempt>
}
