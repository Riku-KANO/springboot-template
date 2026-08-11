package com.example.template.adapter.persistence.settlement

import arrow.core.NonEmptyList
import com.example.template.adapter.persistence.order.OrderRepositoryAdapter
import com.example.template.adapter.persistence.testsupport.PostgresIntegrationTest
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.testfixtures.shouldBeRight
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.awaitOneOrNull
import java.time.Instant
import java.util.Currency
import java.util.UUID

/**
 * [SettlementRepositoryAdapter] の統合テスト。
 *
 * SettlementRepository ポートは `recordOutcome` の1メソッドしか持たず読み出しは無いため、
 * 「書いた結果が正しいテーブル・正しい列に入っているか」を確認するには生の SQL で
 * settlements / settlement_errors を直接クエリするしかない。ReconciliationOutcome の
 * 4バリアントを、意図した2テーブル (正常系 / 要確認) へ正しく振り分けていることを検証する。
 */
class SettlementRepositoryAdapterTest : PostgresIntegrationTest() {
    private val orderRepository by lazy { OrderRepositoryAdapter(databaseClient, transactionalOperator) }
    private val settlementRepository by lazy { SettlementRepositoryAdapter(databaseClient, transactionalOperator) }

    private val jpy: Currency = Currency.getInstance("JPY")

    // settlements/settlement_errors.order_id は orders(id) への FK なので、
    // 先に有効な注文を1件用意しておく必要がある。
    private suspend fun persistOrder(): OrderId {
        val id = OrderId.create("order-${UUID.randomUUID()}").shouldBeRight()
        val order =
            Order(
                id = id,
                customerId = CustomerId.create("customer-1").shouldBeRight(),
                lines = NonEmptyList(OrderLine.createFailFast("SKU-1", 1, 1_000, jpy).shouldBeRight(), emptyList()),
                address = ShippingAddress("Name", "100-0001", "Tokyo", "Chiyoda", "1-1", null),
                status = OrderStatus.PendingPayment,
            )
        orderRepository.save(order).shouldBeRight()
        return id
    }

    private fun sampleRecord(
        orderId: OrderId,
        amountMinor: Long = 2_000,
    ): SettlementRecord =
        SettlementRecord(
            orderId = orderId,
            settledAmount = Money(MoneyMinor.create(amountMinor).shouldBeRight(), jpy),
            settledAt = Instant.parse("2024-02-01T00:00:00Z"),
            providerTransactionId = "txn-${UUID.randomUUID()}",
        )

    private suspend fun countRows(
        table: String,
        orderId: OrderId,
    ): Long =
        databaseClient
            .sql("SELECT COUNT(*) AS cnt FROM $table WHERE order_id = :orderId")
            .bind("orderId", orderId.value)
            .map { row, _ -> row.get("cnt", Long::class.javaObjectType) ?: 0L }
            .awaitOneOrNull() ?: 0L

    @Test
    fun `Matched is written to the settlements table only`() =
        runTest {
            val orderId = persistOrder()
            val record = sampleRecord(orderId)

            settlementRepository
                .recordOutcome(record, ReconciliationOutcome.Matched, Instant.parse("2024-02-01T00:05:00Z"))
                .shouldBeRight()

            assertEquals(1L, countRows("settlements", orderId))
            assertEquals(0L, countRows("settlement_errors", orderId))
        }

    @Test
    fun `AlreadySettled is written to the settlements table only`() =
        runTest {
            val orderId = persistOrder()
            val record = sampleRecord(orderId)

            settlementRepository
                .recordOutcome(record, ReconciliationOutcome.AlreadySettled, Instant.parse("2024-02-01T00:05:00Z"))
                .shouldBeRight()

            assertEquals(1L, countRows("settlements", orderId))
            assertEquals(0L, countRows("settlement_errors", orderId))
        }

    @Test
    fun `AmountMismatch is written to the settlement_errors table with expected+actual payload`() =
        runTest {
            val orderId = persistOrder()
            val record = sampleRecord(orderId, amountMinor = 999)
            val outcome =
                ReconciliationOutcome.AmountMismatch(
                    expected = Money(MoneyMinor.create(1_000).shouldBeRight(), jpy),
                    actual = record.settledAmount,
                )

            settlementRepository
                .recordOutcome(record, outcome, Instant.parse("2024-02-01T00:05:00Z"))
                .shouldBeRight()

            assertEquals(0L, countRows("settlements", orderId))
            assertEquals(1L, countRows("settlement_errors", orderId))

            val expectedMinor =
                databaseClient
                    .sql("SELECT expected_amount_minor FROM settlement_errors WHERE order_id = :orderId")
                    .bind("orderId", orderId.value)
                    .map { row, _ -> row.get("expected_amount_minor", Long::class.javaObjectType) ?: 0L }
                    .awaitOneOrNull()
            assertEquals(1_000L, expectedMinor)
        }

    @Test
    fun `OrderNotSettleable is written to the settlement_errors table with the order status payload`() =
        runTest {
            val orderId = persistOrder()
            val record = sampleRecord(orderId)
            val outcome = ReconciliationOutcome.OrderNotSettleable(status = OrderStatus.Cancelled("no longer needed"))

            settlementRepository
                .recordOutcome(record, outcome, Instant.parse("2024-02-01T00:05:00Z"))
                .shouldBeRight()

            assertEquals(0L, countRows("settlements", orderId))
            assertEquals(1L, countRows("settlement_errors", orderId))

            val statusTypeAndReason =
                databaseClient
                    .sql(
                        "SELECT order_status_type, order_status_cancelled_reason " +
                            "FROM settlement_errors WHERE order_id = :orderId",
                    ).bind("orderId", orderId.value)
                    .map { row, _ ->
                        val type = row.get("order_status_type", String::class.java) ?: ""
                        val reason = row.get("order_status_cancelled_reason", String::class.java) ?: ""
                        type to reason
                    }.awaitOneOrNull()
            assertEquals("CANCELLED" to "no longer needed", statusTypeAndReason)
        }

    @Test
    fun `same provider transaction is recorded only once across retries`() =
        runTest {
            val orderId = persistOrder()
            val record = sampleRecord(orderId)
            val recordedAt = Instant.parse("2024-02-01T00:05:00Z")

            settlementRepository.recordOutcome(record, ReconciliationOutcome.Matched, recordedAt).shouldBeRight()
            settlementRepository.recordOutcome(record, ReconciliationOutcome.AlreadySettled, recordedAt).shouldBeRight()

            assertEquals(1L, countRows("settlements", orderId))
            assertEquals(0L, countRows("settlement_errors", orderId))
        }
}
