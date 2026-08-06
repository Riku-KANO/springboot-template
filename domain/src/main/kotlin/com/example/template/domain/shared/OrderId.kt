package com.example.template.domain.shared

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.ValidationError
import com.example.template.domain.error.ValueError

/**
 * 注文の識別子。
 *
 * private constructor + companion object のスマートコンストラクタという組み合わせにより、
 * 「不正な値を持つ OrderId のインスタンスはそもそも存在できない」ことを型で保証する。
 * コンストラクタが private である限り、このファイルの外から `OrderId("")` のような
 * 直接生成は一切コンパイルできない。
 */
@JvmInline
value class OrderId private constructor(
    val value: String,
) {
    companion object {
        fun create(raw: String): Either<ValidationError, OrderId> =
            either {
                val trimmed = raw.trim()
                ensure(trimmed.isNotEmpty()) { ValueError.BlankOrderId }
                ensure(trimmed.length <= ValueError.MAX_ID_LENGTH) { ValueError.OrderIdTooLong(trimmed.length) }
                OrderId(trimmed)
            }
    }
}
