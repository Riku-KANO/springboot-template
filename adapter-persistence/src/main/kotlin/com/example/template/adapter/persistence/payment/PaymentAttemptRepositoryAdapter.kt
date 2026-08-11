package com.example.template.adapter.persistence.payment

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import com.example.template.adapter.persistence.describeForRepository
import com.example.template.application.port.PaymentAttempt
import com.example.template.application.port.PaymentAttemptRepository
import com.example.template.application.port.PaymentAttemptStatus
import com.example.template.application.port.PaymentCharge
import com.example.template.application.port.PaymentIdempotencyKey
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.time.Instant
import java.util.Currency

private const val SELECT_BY_ORDER_ID = """
    SELECT idempotency_key, order_id, amount_minor, currency, status, paid_at, provider_transaction_id
    FROM payment_attempts
    WHERE order_id = :orderId
"""

private const val SELECT_BY_KEY = """
    SELECT idempotency_key, order_id, amount_minor, currency, status, paid_at, provider_transaction_id
    FROM payment_attempts
    WHERE idempotency_key = :idempotencyKey
"""

private const val INSERT_PENDING = """
    INSERT INTO payment_attempts (idempotency_key, order_id, amount_minor, currency, status)
    VALUES (:idempotencyKey, :orderId, :amountMinor, :currency, 'PENDING')
    ON CONFLICT (idempotency_key) DO NOTHING
"""

private const val MARK_SUCCEEDED = """
    UPDATE payment_attempts
    SET status = 'SUCCEEDED', paid_at = :paidAt, provider_transaction_id = :providerTransactionId,
        updated_at = CURRENT_TIMESTAMP
    WHERE idempotency_key = :idempotencyKey AND status = 'PENDING'
"""

/** PostgreSQL-backed implementation of the durable payment process state. */
class PaymentAttemptRepositoryAdapter(
    private val databaseClient: DatabaseClient,
) : PaymentAttemptRepository {
    override suspend fun findByOrderId(orderId: OrderId): Either<OrderError, PaymentAttempt?> =
        fetch(SELECT_BY_ORDER_ID) { it.bind("orderId", orderId.value) }

    override suspend fun findOrCreate(
        orderId: OrderId,
        idempotencyKey: PaymentIdempotencyKey,
        amount: Money,
    ): Either<OrderError, PaymentAttempt> =
        either {
            execute {
                databaseClient
                    .sql(INSERT_PENDING)
                    .bind("idempotencyKey", idempotencyKey.value)
                    .bind("orderId", orderId.value)
                    .bind("amountMinor", amount.amount.value)
                    .bind("currency", amount.currency.currencyCode)
                    .fetch()
                    .awaitRowsUpdated()
            }.bind()

            val stored = ensureNotNull(findByKey(idempotencyKey).bind()) { unavailable("payment attempt disappeared after insert") }
            ensure(stored.orderId == orderId && stored.idempotencyKey == idempotencyKey && stored.amount == amount) {
                unavailable("idempotency key was reused with different payment parameters")
            }
            stored
        }

    override suspend fun markSucceeded(
        idempotencyKey: PaymentIdempotencyKey,
        charge: PaymentCharge,
    ): Either<OrderError, PaymentAttempt> =
        either {
            execute {
                databaseClient
                    .sql(MARK_SUCCEEDED)
                    .bind("paidAt", charge.paidAt)
                    .bind("providerTransactionId", charge.providerTransactionId)
                    .bind("idempotencyKey", idempotencyKey.value)
                    .fetch()
                    .awaitRowsUpdated()
            }.bind()

            val stored = ensureNotNull(findByKey(idempotencyKey).bind()) { unavailable("payment attempt not found") }
            val succeeded = stored.status as? PaymentAttemptStatus.Succeeded
            ensure(succeeded?.charge == charge) {
                unavailable("payment provider returned a different result for the same idempotency key")
            }
            stored
        }

    private suspend fun findByKey(idempotencyKey: PaymentIdempotencyKey): Either<OrderError, PaymentAttempt?> =
        fetch(SELECT_BY_KEY) { it.bind("idempotencyKey", idempotencyKey.value) }

    private suspend fun fetch(
        sql: String,
        bind: (DatabaseClient.GenericExecuteSpec) -> DatabaseClient.GenericExecuteSpec,
    ): Either<OrderError, PaymentAttempt?> =
        Either
            .catch {
                bind(databaseClient.sql(sql))
                    .map { row, _ ->
                        val paidAt = row.get("paid_at", Instant::class.java)
                        val providerTransactionId = row.get("provider_transaction_id", String::class.java)
                        PaymentAttempt(
                            orderId = OrderId.create(row.get("order_id", String::class.java)!!).getOrNull()!!,
                            idempotencyKey = PaymentIdempotencyKey(row.get("idempotency_key", String::class.java)!!),
                            amount =
                                Money(
                                    MoneyMinor.create(row.get("amount_minor", Long::class.javaObjectType)!!).getOrNull()!!,
                                    Currency.getInstance(row.get("currency", String::class.java)!!),
                                ),
                            status =
                                when (val status = row.get("status", String::class.java)!!) {
                                    "PENDING" -> PaymentAttemptStatus.Pending
                                    "SUCCEEDED" ->
                                        PaymentAttemptStatus.Succeeded(
                                            PaymentCharge(
                                                paidAt = requireNotNull(paidAt),
                                                providerTransactionId = requireNotNull(providerTransactionId),
                                            ),
                                        )
                                    else -> error("unknown payment attempt status: $status")
                                },
                        )
                    }.awaitOneOrNull()
            }.mapLeft { unavailable(it.describeForRepository()) }

    private suspend fun execute(block: suspend () -> Long): Either<OrderError, Long> =
        Either.catch { block() }.mapLeft { unavailable(it.describeForRepository()) }

    private fun unavailable(cause: String): OrderError.RepositoryUnavailable = OrderError.RepositoryUnavailable(cause)
}
