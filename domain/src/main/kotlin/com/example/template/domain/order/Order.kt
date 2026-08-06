package com.example.template.domain.order

import arrow.core.Either
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.optics.optics
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.ValidationError
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress

/**
 * 注文集約のルート。
 *
 * lines を [NonEmptyList] にしているのは「明細が1件も無い注文」を型で表現不可能にするため
 * (バリデーション関数の中で `isEmpty()` をチェックし忘れる、という種類のバグを消せる)。
 */
@optics
data class Order(
    val id: OrderId,
    val customerId: CustomerId,
    val lines: NonEmptyList<OrderLine>,
    val address: ShippingAddress,
    val status: OrderStatus,
) {
    companion object {
        /**
         * 集約レベルの業務ルール (ここでは SKU の重複禁止) をフェイルファストで検証する。
         *
         * OrderLine.create が「フィールド単位」の累積バリデーションを行うのに対し、
         * こちらは「集約全体で見たときに初めて分かる」ルールなので、
         * 個々の明細のバリデーションとは別の関数として用意している。
         * 複数エラーを溜め込む理由がない (最初の重複が見つかった時点で不正確定) ため、
         * ここではあえてフェイルファストの `either { ensure(...) }` を使う。
         */
        fun create(
            id: OrderId,
            customerId: CustomerId,
            lines: NonEmptyList<OrderLine>,
            address: ShippingAddress,
        ): Either<OrderError, Order> =
            either {
                val skus = lines.all.map { it.sku }
                val duplicated =
                    skus
                        .groupingBy { it }
                        .eachCount()
                        .filterValues { it > 1 }
                        .keys
                ensure(duplicated.isEmpty()) {
                    OrderError.InvalidOrderLine("duplicate sku(s): ${duplicated.joinToString { it.value }}")
                }
                Order(id, customerId, lines, address, OrderStatus.Draft)
            }
    }

    /** 全明細の小計を合算した注文合計金額。通貨不一致やオーバーフローは ValidationError として畳み込む。 */
    fun total(): Either<ValidationError, Money> =
        either {
            val currency = lines.head.unitPrice.currency
            lines.fold(Money.zero(currency)) { acc, line ->
                val lineTotal = line.lineTotal().bind()
                acc.plus(lineTotal).bind()
            }
        }
}
