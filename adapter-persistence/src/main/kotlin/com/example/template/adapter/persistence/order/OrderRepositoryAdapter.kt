package com.example.template.adapter.persistence.order

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensureNotNull
import com.example.template.adapter.persistence.bindNullable
import com.example.template.adapter.persistence.describeForRepository
import com.example.template.application.port.OrderRepository
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.shared.OrderId
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.r2dbc.core.flow
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Instant

private const val SELECT_ORDER_SQL = """
    SELECT id, customer_id, recipient_name, postal_code, prefecture, city, address_line1, address_line2,
           status_type, status_paid_at, status_fulfilling_started_at, status_tracking_number,
           status_delivered_at, status_cancelled_reason, status_refunded_at,
           status_refunded_amount_minor, status_refunded_amount_currency
    FROM orders
    WHERE id = :id
"""

private const val SELECT_ORDER_LINES_SQL = """
    SELECT line_no, sku, quantity, unit_price_minor, unit_price_currency
    FROM order_lines
    WHERE order_id = :orderId
    ORDER BY line_no
"""

private const val UPSERT_ORDER_SQL = """
    INSERT INTO orders (
        id, customer_id, recipient_name, postal_code, prefecture, city, address_line1, address_line2,
        status_type, status_paid_at, status_fulfilling_started_at, status_tracking_number,
        status_delivered_at, status_cancelled_reason, status_refunded_at,
        status_refunded_amount_minor, status_refunded_amount_currency
    ) VALUES (
        :id, :customerId, :recipientName, :postalCode, :prefecture, :city, :addressLine1, :addressLine2,
        :statusType, :statusPaidAt, :statusFulfillingStartedAt, :statusTrackingNumber,
        :statusDeliveredAt, :statusCancelledReason, :statusRefundedAt,
        :statusRefundedAmountMinor, :statusRefundedAmountCurrency
    )
    ON CONFLICT (id) DO UPDATE SET
        customer_id = EXCLUDED.customer_id,
        recipient_name = EXCLUDED.recipient_name,
        postal_code = EXCLUDED.postal_code,
        prefecture = EXCLUDED.prefecture,
        city = EXCLUDED.city,
        address_line1 = EXCLUDED.address_line1,
        address_line2 = EXCLUDED.address_line2,
        status_type = EXCLUDED.status_type,
        status_paid_at = EXCLUDED.status_paid_at,
        status_fulfilling_started_at = EXCLUDED.status_fulfilling_started_at,
        status_tracking_number = EXCLUDED.status_tracking_number,
        status_delivered_at = EXCLUDED.status_delivered_at,
        status_cancelled_reason = EXCLUDED.status_cancelled_reason,
        status_refunded_at = EXCLUDED.status_refunded_at,
        status_refunded_amount_minor = EXCLUDED.status_refunded_amount_minor,
        status_refunded_amount_currency = EXCLUDED.status_refunded_amount_currency
"""

private const val DELETE_ORDER_LINES_SQL = "DELETE FROM order_lines WHERE order_id = :orderId"

private const val INSERT_ORDER_LINE_SQL = """
    INSERT INTO order_lines (order_id, line_no, sku, quantity, unit_price_minor, unit_price_currency)
    VALUES (:orderId, :lineNo, :sku, :quantity, :unitPriceMinor, :unitPriceCurrency)
"""

/**
 * [OrderRepository] の R2DBC 実装。
 *
 * Order 集約 (Order + NonEmptyList<OrderLine>) を「集約丸ごと」読み書きする。findById は
 * orders 1行 + order_lines N行の2クエリを行い [toDomainOrder] で1つの Order に組み立てる。
 * save は upsert (orders への INSERT ... ON CONFLICT) の後、order_lines を
 * 全削除 → 全件再挿入する「置き換え」方式にしている。差分更新 (増えた行だけ INSERT、
 * 減った行だけ DELETE) にしない理由: Order.lines は NonEmptyList であり「何番目の行が
 * 何に対応するか」を呼び出し側が意識する必要のない値オブジェクトの列である。差分検出を
 * 行うと「同じ内容の行が line_no だけ変わって並び替わった」ケースを誤って
 * 更新/削除/挿入の組み合わせで表現してしまいバグの温床になるため、
 * 「常に全消去してから今の状態を丸ごと書き直す」方が単純かつ正しい。
 *
 * save は upsert + 全削除 + 再挿入という複数の SQL 文から成るが、:application 層の
 * ユースケース (例: CreateOrderService) はこれを「1回の I/O」として扱い、自前で
 * TxRunner に包んでいない。そのため、この複数文の原子性はこのアダプタ自身が
 * [transactionalOperator] で保証する必要がある (R2DBC はデフォルトで文ごとに
 * auto-commit するため、明示的なトランザクション境界なしに複数文を実行すると
 * 部分的な書き込みが残る恐れがある)。R2DBC のトランザクション伝播はデフォルトで
 * REQUIRED (既存のトランザクションがあれば参加、無ければ新規作成) なので、
 * :application 層の TxRunner (R2dbcTxRunner) が張った外側のトランザクションの中から
 * save が呼ばれた場合は、その外側のトランザクションにそのまま参加する
 * (:bootstrap 側で TransactionalOperator を単一の Bean として両方に注入する前提)。
 */
class OrderRepositoryAdapter(
    private val databaseClient: DatabaseClient,
    private val transactionalOperator: TransactionalOperator,
) : OrderRepository {
    override suspend fun findById(id: OrderId): Either<OrderError, Order> =
        either {
            val orderRow = fetchOrderRow(id).bind()
            ensureNotNull(orderRow) { OrderError.OrderNotFound(id) }
            val lineRows = fetchOrderLineRows(id).bind()
            toDomainOrder(orderRow, lineRows).bind()
        }

    override suspend fun save(order: Order): Either<OrderError, Order> =
        // ##### 例外の世界から Either の世界への変換境界 #####
        // ドライバ (r2dbc-postgresql) が投げうる例外 (接続不可・一意制約違反等) は
        // ここで初めて catch し、二度と呼び出し元 (:application) へ例外の形では渡さない。
        Either
            .catch {
                transactionalOperator.executeAndAwait {
                    upsertOrderRow(order)
                    replaceOrderLines(order)
                }
                order
            }.mapLeft { OrderError.RepositoryUnavailable(it.describeForRepository()) }

    private suspend fun fetchOrderRow(id: OrderId): Either<OrderError, OrderRow?> =
        Either
            .catch {
                databaseClient
                    .sql(SELECT_ORDER_SQL)
                    .bind("id", id.value)
                    .map { row, _ -> row.toOrderRow() }
                    .awaitOneOrNull()
            }.mapLeft { OrderError.RepositoryUnavailable(it.describeForRepository()) }

    private suspend fun fetchOrderLineRows(id: OrderId): Either<OrderError, List<OrderLineRow>> =
        Either
            .catch {
                databaseClient
                    .sql(SELECT_ORDER_LINES_SQL)
                    .bind("orderId", id.value)
                    .map { row, _ -> row.toOrderLineRow() }
                    .flow()
                    .toList()
            }.mapLeft { OrderError.RepositoryUnavailable(it.describeForRepository()) }

    private suspend fun upsertOrderRow(order: Order) {
        val columns = order.status.toColumns()
        databaseClient
            .sql(UPSERT_ORDER_SQL)
            .bind("id", order.id.value)
            .bind("customerId", order.customerId.value)
            .bind("recipientName", order.address.recipientName)
            .bind("postalCode", order.address.postalCode)
            .bind("prefecture", order.address.prefecture)
            .bind("city", order.address.city)
            .bind("addressLine1", order.address.addressLine1)
            .bindNullable("addressLine2", order.address.addressLine2, String::class.java)
            .bind("statusType", columns.type)
            .bindNullable("statusPaidAt", columns.paidAt, Instant::class.java)
            .bindNullable("statusFulfillingStartedAt", columns.fulfillingStartedAt, Instant::class.java)
            .bindNullable("statusTrackingNumber", columns.trackingNumber, String::class.java)
            .bindNullable("statusDeliveredAt", columns.deliveredAt, Instant::class.java)
            .bindNullable("statusCancelledReason", columns.cancelledReason, String::class.java)
            .bindNullable("statusRefundedAt", columns.refundedAt, Instant::class.java)
            .bindNullable("statusRefundedAmountMinor", columns.refundedAmountMinor, Long::class.javaObjectType)
            .bindNullable("statusRefundedAmountCurrency", columns.refundedAmountCurrency, String::class.java)
            .fetch()
            .awaitRowsUpdated()
    }

    private suspend fun replaceOrderLines(order: Order) {
        databaseClient
            .sql(DELETE_ORDER_LINES_SQL)
            .bind("orderId", order.id.value)
            .fetch()
            .awaitRowsUpdated()

        order.lines.all.forEachIndexed { index, line ->
            databaseClient
                .sql(INSERT_ORDER_LINE_SQL)
                .bind("orderId", order.id.value)
                .bind("lineNo", index)
                .bind("sku", line.sku.value)
                .bind("quantity", line.quantity.value)
                .bind("unitPriceMinor", line.unitPrice.amount.value)
                .bind("unitPriceCurrency", line.unitPrice.currency.currencyCode)
                .fetch()
                .awaitRowsUpdated()
        }
    }
}
