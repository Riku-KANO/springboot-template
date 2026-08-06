package com.example.template.domain.shared

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.ValidationError
import com.example.template.domain.error.ValueError

/** 顧客の識別子。設計意図は [OrderId] を参照。 */
@JvmInline
value class CustomerId private constructor(
    val value: String,
) {
    companion object {
        fun create(raw: String): Either<ValidationError, CustomerId> =
            either {
                val trimmed = raw.trim()
                ensure(trimmed.isNotEmpty()) { ValueError.BlankCustomerId }
                ensure(trimmed.length <= ValueError.MAX_ID_LENGTH) { ValueError.CustomerIdTooLong(trimmed.length) }
                CustomerId(trimmed)
            }
    }
}
