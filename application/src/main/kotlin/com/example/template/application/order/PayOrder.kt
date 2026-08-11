package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.PaymentAttempt
import com.example.template.application.port.PaymentAttemptRepository
import com.example.template.application.port.PaymentAttemptStatus
import com.example.template.application.port.PaymentCharge
import com.example.template.application.port.PaymentGatewayPort
import com.example.template.application.port.PaymentIdempotencyKey
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.order.paymentDue
import com.example.template.domain.order.submitPayment
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId

data class PayOrderCommand(
    val orderId: OrderId,
)

/**
 * 注文の決済を確定するユースケース。
 * 「注文を読む → 決済ゲートウェイに請求する → submitPayment 遷移を適用する → 保存する」という
 * 複数ステップの中で判断を下すのは domain (`Order.total` / `submitPayment`) だけであり、
 * このユースケース自体は各ステップをオーケストレーションするだけでビジネスルールを持たない。
 */
fun interface PayOrder {
    suspend operator fun invoke(command: PayOrderCommand): Either<OrderError, Order>
}

/**
 * [PayOrder] の実装。
 *
 * 外部決済はDBトランザクションでは取り消せないため、トランザクションの内側では呼ばない。
 * 代わりに「準備 → 外部決済 → 結果記録 → 注文確定」を短いDBトランザクションで区切り、
 * 同じ冪等キーを全リトライで使う。結果記録を注文確定と分けることで、並行キャンセル等により
 * 注文更新が失敗しても、補償対象となる決済成功の証跡は失われない。
 */
class PayOrderService(
    private val orderRepository: OrderRepository,
    private val paymentAttemptRepository: PaymentAttemptRepository,
    private val paymentGatewayPort: PaymentGatewayPort,
    private val txRunner: TxRunner,
) : PayOrder {
    override suspend fun invoke(command: PayOrderCommand): Either<OrderError, Order> =
        when (val preparation = prepare(command)) {
            is Either.Left -> preparation
            is Either.Right ->
                when (val prepared = preparation.value) {
                    is PreparedPayment.AlreadyPaid -> Either.Right(prepared.order)
                    is PreparedPayment.ChargeRequired -> chargeAndFinalize(prepared)
                }
        }

    private suspend fun prepare(command: PayOrderCommand): Either<OrderError, PreparedPayment> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                val existing = paymentAttemptRepository.findByOrderId(order.id).bind()
                if (order.status is OrderStatus.Paid && existing?.status is PaymentAttemptStatus.Succeeded) {
                    PreparedPayment.AlreadyPaid(order)
                } else {
                    val amount = order.paymentDue().bind()
                    val attempt =
                        paymentAttemptRepository
                            .findOrCreate(order.id, PaymentIdempotencyKey.forOrder(order.id), amount)
                            .bind()
                    PreparedPayment.ChargeRequired(order.id, amount, attempt)
                }
            }
        }

    private suspend fun chargeAndFinalize(prepared: PreparedPayment.ChargeRequired): Either<OrderError, Order> {
        val charge =
            when (val status = prepared.attempt.status) {
                PaymentAttemptStatus.Pending ->
                    paymentGatewayPort
                        .charge(prepared.orderId, prepared.amount, prepared.attempt.idempotencyKey)
                        .fold({ return Either.Left(it) }, { it })

                is PaymentAttemptStatus.Succeeded -> status.charge
            }

        when (val persisted = persistCharge(prepared, charge)) {
            is Either.Left -> return persisted
            is Either.Right -> Unit
        }

        val finalized = finalizePayment(prepared, charge)
        return if (finalized is Either.Left && finalized.value is OrderError.ConcurrentModification) {
            recoverConcurrentFinalization(prepared)
        } else {
            finalized
        }
    }

    private suspend fun persistCharge(
        prepared: PreparedPayment.ChargeRequired,
        charge: PaymentCharge,
    ): Either<OrderError, PaymentAttempt> =
        txRunner.transactional {
            paymentAttemptRepository.markSucceeded(prepared.attempt.idempotencyKey, charge)
        }

    private suspend fun finalizePayment(
        prepared: PreparedPayment.ChargeRequired,
        charge: PaymentCharge,
    ): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val currentOrder = orderRepository.findById(prepared.orderId).bind()
                val currentAttempt = paymentAttemptRepository.findByOrderId(prepared.orderId).bind()
                if (currentOrder.status is OrderStatus.Paid && currentAttempt?.status is PaymentAttemptStatus.Succeeded) {
                    currentOrder
                } else {
                    val succeeded = currentAttempt?.status as? PaymentAttemptStatus.Succeeded
                    if (succeeded?.charge != charge) {
                        raise(OrderError.RepositoryUnavailable("durable payment result does not match provider result"))
                    }
                    val currentAmount = currentOrder.paymentDue().bind()
                    if (currentAmount != prepared.amount) {
                        raise(OrderError.ConcurrentModification(prepared.orderId))
                    }
                    orderRepository.save(currentOrder.submitPayment(charge.paidAt).bind()).bind()
                }
            }
        }

    private suspend fun recoverConcurrentFinalization(prepared: PreparedPayment.ChargeRequired): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(prepared.orderId).bind()
                val attempt = paymentAttemptRepository.findByOrderId(prepared.orderId).bind()
                if (order.status is OrderStatus.Paid && attempt?.status is PaymentAttemptStatus.Succeeded) {
                    order
                } else {
                    raise(OrderError.ConcurrentModification(prepared.orderId))
                }
            }
        }
}

private sealed interface PreparedPayment {
    data class AlreadyPaid(
        val order: Order,
    ) : PreparedPayment

    data class ChargeRequired(
        val orderId: OrderId,
        val amount: Money,
        val attempt: PaymentAttempt,
    ) : PreparedPayment
}
