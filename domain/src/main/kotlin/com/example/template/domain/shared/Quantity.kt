package com.example.template.domain.shared

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.ValidationError
import com.example.template.domain.error.ValueError

/** 注文明細の数量。0以下・上限超えの両方をスマートコンストラクタで排除する。 */
@JvmInline
value class Quantity private constructor(
    val value: Int,
) {
    companion object {
        fun create(raw: Int): Either<ValidationError, Quantity> =
            either {
                ensure(raw > 0) { ValueError.NonPositiveQuantity(raw) }
                ensure(raw <= ValueError.MAX_QUANTITY) { ValueError.QuantityTooLarge(raw) }
                Quantity(raw)
            }
    }
}
