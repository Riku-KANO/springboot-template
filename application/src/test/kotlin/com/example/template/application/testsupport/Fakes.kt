package com.example.template.application.testsupport

import arrow.core.Either
import arrow.core.right
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.PaymentAttempt
import com.example.template.application.port.PaymentAttemptRepository
import com.example.template.application.port.PaymentAttemptStatus
import com.example.template.application.port.PaymentCharge
import com.example.template.application.port.PaymentGatewayPort
import com.example.template.application.port.PaymentIdempotencyKey
import com.example.template.application.port.SettlementRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.SettlementError
import com.example.template.domain.order.Order
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId
import java.time.Instant

/*
 * このモジュールのポートは全て単純な suspend 関数の集まり (fun interface か、多くても
 * 数メソッドのインターフェース) なので、MockK の `coEvery { ... } returns ...` を毎テストで
 * 書くよりも、状態を持つ手書きフェイクを1つ用意して「呼ばれた回数」「渡された引数」を
 * 素直にプロパティとして覗ける方がテストの意図が読みやすい。特に TxRunner のように
 * 「ブロックを実行してその結果を観察する」という *動作* 自体を検証したいケースでは、
 * モックのスタブ設定より本物に近い挙動を持つフェイクの方が自然に書ける。
 * そのため本モジュールでは MockK ではなく手書きフェイクを一貫して採用する。
 */

/** インメモリの OrderRepository フェイク。save が呼ばれたかどうかを検証できるよう記録する。 */
class FakeOrderRepository(
    initial: Order? = null,
    additional: List<Order> = emptyList(),
) : OrderRepository {
    private val store = mutableMapOf<OrderId, Order>()
    val savedOrders = mutableListOf<Order>()

    init {
        initial?.let { store[it.id] = it }
        additional.forEach { store[it.id] = it }
    }

    override suspend fun findById(id: OrderId): Either<OrderError, Order> = store[id]?.right() ?: Either.Left(OrderError.OrderNotFound(id))

    override suspend fun findPage(
        after: OrderId?,
        limit: Int,
    ): Either<OrderError, List<Order>> =
        store.values
            .sortedBy { it.id.value }
            .filter { after == null || it.id.value > after.value }
            .take(limit)
            .right()

    override suspend fun save(order: Order): Either<OrderError, Order> {
        savedOrders += order
        store[order.id] = order
        return order.right()
    }
}

/** 常に指定した Either を返す PaymentGatewayPort フェイク。呼び出し回数を記録する。 */
class FakePaymentGatewayPort(
    private val result: Either<OrderError, PaymentCharge>,
) : PaymentGatewayPort {
    var invocationCount: Int = 0
        private set
    val receivedIdempotencyKeys = mutableListOf<PaymentIdempotencyKey>()

    override suspend fun charge(
        orderId: OrderId,
        amount: Money,
        idempotencyKey: PaymentIdempotencyKey,
    ): Either<OrderError, PaymentCharge> {
        invocationCount++
        receivedIdempotencyKeys += idempotencyKey
        return result
    }
}

/** 永続的な決済処理状態を模倣するインメモリ実装。 */
class FakePaymentAttemptRepository(
    initial: PaymentAttempt? = null,
) : PaymentAttemptRepository {
    private val attempts = mutableMapOf<OrderId, PaymentAttempt>()

    init {
        initial?.let { attempts[it.orderId] = it }
    }

    override suspend fun findByOrderId(orderId: OrderId): Either<OrderError, PaymentAttempt?> = Either.Right(attempts[orderId])

    override suspend fun findOrCreate(
        orderId: OrderId,
        idempotencyKey: PaymentIdempotencyKey,
        amount: Money,
    ): Either<OrderError, PaymentAttempt> {
        val attempt = attempts.getOrPut(orderId) { PaymentAttempt(orderId, idempotencyKey, amount, PaymentAttemptStatus.Pending) }
        return if (attempt.idempotencyKey == idempotencyKey && attempt.amount == amount) {
            Either.Right(attempt)
        } else {
            Either.Left(OrderError.RepositoryUnavailable("idempotency key reused with different parameters"))
        }
    }

    override suspend fun markSucceeded(
        idempotencyKey: PaymentIdempotencyKey,
        charge: PaymentCharge,
    ): Either<OrderError, PaymentAttempt> {
        val current =
            attempts.values.singleOrNull { it.idempotencyKey == idempotencyKey }
                ?: return Either.Left(OrderError.RepositoryUnavailable("payment attempt not found"))
        val succeeded = current.copy(status = PaymentAttemptStatus.Succeeded(charge))
        attempts[current.orderId] = succeeded
        return Either.Right(succeeded)
    }
}

/** 記録した SettlementRecord/Outcome を検証用に保持する SettlementRepository フェイク。 */
class FakeSettlementRepository(
    private val result: Either<SettlementError, Unit> = Either.Right(Unit),
) : SettlementRepository {
    data class Recorded(
        val record: SettlementRecord,
        val outcome: ReconciliationOutcome,
        val recordedAt: Instant,
    )

    val recorded = mutableListOf<Recorded>()

    override suspend fun recordOutcome(
        record: SettlementRecord,
        outcome: ReconciliationOutcome,
        recordedAt: Instant,
    ): Either<SettlementError, Unit> {
        recorded += Recorded(record, outcome, recordedAt)
        return result
    }
}

/**
 * 「トランザクション境界を実際に通ったか」「渡されたブロックの結果が Left だったか」を
 * 記録するだけの TxRunner フェイク。本物のロールバックは行わない (:application にはそもそも
 * ロールバックすべき副作用の実体、つまり DB がまだ無い) が、「Left で終わった場合に
 * それ以降の処理 (repository.save 等) が実際に呼ばれていないか」は各ユースケースのテストの中で
 * フェイクリポジトリの記録を見て検証する。TxRunner 自体としては、渡されたブロックをそのまま
 * 実行して結果を横取りするだけで十分。
 */
class RecordingTxRunner : TxRunner {
    var invocationCount: Int = 0
        private set
    var lastResultWasLeft: Boolean? = null
        private set

    override suspend fun <E, A> transactional(block: suspend () -> Either<E, A>): Either<E, A> {
        invocationCount++
        val result = block()
        lastResultWasLeft = result.isLeft()
        return result
    }
}
