package com.example.template.domain.shared

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.ValidationError
import com.example.template.domain.error.ValueError

/**
 * 商品を一意に識別する SKU コード。
 * 大文字英数字とハイフンのみ、1〜32文字という業務ルールをスマートコンストラクタで強制する。
 */
@JvmInline
value class Sku private constructor(
    val value: String,
) {
    companion object {
        private val PATTERN = Regex("^[A-Z0-9-]{1,32}$")

        fun create(raw: String): Either<ValidationError, Sku> =
            either {
                val normalized = raw.trim().uppercase()
                ensure(PATTERN.matches(normalized)) { ValueError.InvalidSkuFormat(raw) }
                Sku(normalized)
            }
    }
}
