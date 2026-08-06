package com.example.template.domain.order

import arrow.core.Either
import arrow.core.EitherNel
import arrow.core.raise.either
import arrow.core.raise.zipOrAccumulate
import arrow.optics.optics
import com.example.template.domain.error.ValidationError
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.Quantity
import com.example.template.domain.shared.Sku
import java.util.Currency

/** 注文明細1行。sku・数量・単価の3項目を持つ。 */
@optics
data class OrderLine(
    val sku: Sku,
    val quantity: Quantity,
    val unitPrice: Money,
) {
    companion object {
        /**
         * 累積バリデーション版のスマートコンストラクタ。
         *
         * sku・quantity・unitPriceMinor の3項目を `zipOrAccumulate` でそれぞれ独立に検証し、
         * 3つとも不正であれば3件のエラーを含む NonEmptyList を返す。
         * Web API のフォーム入力のように「一度に全部の間違いを教えてほしい」場面向け。
         * (:application 層はこれをそのまま使って「全バリデーションエラーを返す」ユースケースを作る)
         */
        fun create(
            rawSku: String,
            rawQuantity: Int,
            rawUnitPriceMinor: Long,
            currency: Currency,
        ): EitherNel<ValidationError, OrderLine> =
            either {
                zipOrAccumulate(
                    { Sku.create(rawSku).bind() },
                    { Quantity.create(rawQuantity).bind() },
                    { MoneyMinor.create(rawUnitPriceMinor).bind() },
                ) { sku, quantity, minor -> OrderLine(sku, quantity, Money(minor, currency)) }
            }

        /**
         * フェイルファスト版のスマートコンストラクタ。
         *
         * 上の [create] と違い、最初に見つかった1件のエラーで即座に打ち切る。
         * ドメインサービス内部の逐次処理など、「途中で止めて後続の計算を省略したい」場面向け。
         * 入力は [create] と同じ3項目で、違いは「集約するか (Either<NonEmptyList<E>, T>)」
         * 「打ち切るか (Either<E, T>)」だけ。
         */
        fun createFailFast(
            rawSku: String,
            rawQuantity: Int,
            rawUnitPriceMinor: Long,
            currency: Currency,
        ): Either<ValidationError, OrderLine> =
            either {
                val sku = Sku.create(rawSku).bind()
                val quantity = Quantity.create(rawQuantity).bind()
                val minor = MoneyMinor.create(rawUnitPriceMinor).bind()
                OrderLine(sku, quantity, Money(minor, currency))
            }
    }

    /** この明細行の小計 (単価 × 数量)。 */
    fun lineTotal(): Either<ValidationError, Money> = unitPrice.multiply(quantity.value.toLong())
}
