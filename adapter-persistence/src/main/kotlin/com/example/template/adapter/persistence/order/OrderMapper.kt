package com.example.template.adapter.persistence.order

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import java.util.Currency

/**
 * DB から読んだ行 (OrderRow + OrderLineRow の集まり) を、ドメインの Order 集約へ復元する。
 *
 * ここが「行の形をしたただのデータ」から「スマートコンストラクタを通った正当な Order」への
 * 唯一の変換点であり、途中のどのステップが失敗しても (壊れた OrderId・CustomerId、
 * 不正な SKU・金額、空の order_lines 等) 例外ではなく [OrderError.RepositoryUnavailable] として
 * 呼び出し元に返す。これらはいずれも「保存時には決して起こらないはず」の不整合
 * (マイグレーションの手動操作や他経路からの直接 INSERT で壊れた、等) であり、
 * 通常のバリデーションエラー (ValidationError) ではなくインフラ障害として扱う。
 *
 * OrderId.create / CustomerId.create / OrderLine.createFailFast (内部で Sku.create /
 * Quantity.create / MoneyMinor.create を経由する) をすべて経由させているのが
 * 「smart constructor を通す」という要件の実体で、Order 自体の生成だけは
 * (companion object の `Order.create` ではなく) 生の data class コンストラクタを使う。
 * `Order.create` は「新規注文は必ず Draft から始まる」という業務ルールを体現しており
 * status を Draft 固定で返してしまうため、任意の status を復元したいここでは使えない
 * (domain/order/OrderTransitions.kt が遷移後の Order を `copy(status = next)` で
 * 作っているのと同じ理由: 復元・遷移は「新規作成」ではない)。
 */
internal fun toDomainOrder(
    orderRow: OrderRow,
    lineRows: List<OrderLineRow>,
): Either<OrderError, Order> =
    either {
        val id = OrderId.create(orderRow.id).mapLeft { corruptedOrderRow(orderRow.id, "invalid id: ${it.message}") }.bind()
        val customerId =
            CustomerId
                .create(orderRow.customerId)
                .mapLeft { corruptedOrderRow(orderRow.id, "invalid customer_id: ${it.message}") }
                .bind()
        val address =
            ShippingAddress(
                recipientName = orderRow.recipientName,
                postalCode = orderRow.postalCode,
                prefecture = orderRow.prefecture,
                city = orderRow.city,
                addressLine1 = orderRow.addressLine1,
                addressLine2 = orderRow.addressLine2,
            )
        val status = decodeOrderStatus(orderRow).bind()

        val mappedLines = lineRows.sortedBy { it.lineNo }.map { lineRow -> toDomainOrderLine(orderRow.id, lineRow).bind() }
        ensure(mappedLines.isNotEmpty()) { corruptedOrderRow(orderRow.id, "no order_lines rows found") }
        val lines = NonEmptyList(mappedLines.first(), mappedLines.drop(1))

        Order(id, customerId, lines, address, status, orderRow.version)
    }

private fun toDomainOrderLine(
    orderId: String,
    lineRow: OrderLineRow,
): Either<OrderError, OrderLine> =
    either {
        val currency =
            Either
                .catch { Currency.getInstance(lineRow.unitPriceCurrency) }
                .mapLeft { corruptedOrderRow(orderId, "invalid currency code: ${lineRow.unitPriceCurrency}") }
                .bind()
        OrderLine
            .createFailFast(lineRow.sku, lineRow.quantity, lineRow.unitPriceMinor, currency)
            .mapLeft { corruptedOrderRow(orderId, "invalid order_line (line_no=${lineRow.lineNo}): ${it.message}") }
            .bind()
    }
