package com.example.template.domain.shared

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.ValidationError
import com.example.template.domain.error.ValueError
import java.math.BigDecimal
import java.util.Currency

/**
 * 金額 = 数値 (MoneyMinor) + 通貨のペア。
 *
 * Kotlin の multi-field value class は現時点で experimental なため、
 * (指示により) 採用せず通常の data class にしている。そのため amount / currency の
 * 組み合わせ自体が「常に有効」という保証は value class ほど強くはないが、
 * 内部の MoneyMinor がスマートコンストラクタ経由でしか作れない以上、
 * 実務上問題になる不正状態 (負の金額など) は依然として型で排除できている。
 */
data class Money(
    val amount: MoneyMinor,
    val currency: Currency,
) {
    companion object {
        fun zero(currency: Currency): Money = Money(MoneyMinor.ZERO, currency)

        fun of(
            major: BigDecimal,
            currency: Currency,
        ): Either<ValidationError, Money> =
            either {
                Money(MoneyMinor.fromBigDecimal(major, currency).bind(), currency)
            }
    }

    /** 通貨が一致しない加算は「実行時例外」ではなく型で表現された ValidationError として扱う。 */
    fun plus(other: Money): Either<ValidationError, Money> =
        either {
            ensure(currency == other.currency) { ValueError.CurrencyMismatch(currency, other.currency) }
            Money(amount.plus(other.amount).bind(), currency)
        }

    /** 数量倍などの整数倍計算。通貨は変わらないため CurrencyMismatch は起こり得ない。 */
    fun multiply(factor: Long): Either<ValidationError, Money> =
        either {
            Money(amount.multiply(factor).bind(), currency)
        }
}
