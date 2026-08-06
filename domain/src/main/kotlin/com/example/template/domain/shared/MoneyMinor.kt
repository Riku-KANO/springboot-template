package com.example.template.domain.shared

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.ValidationError
import com.example.template.domain.error.ValueError
import java.math.BigDecimal
import java.util.Currency

/**
 * 金額を「最小通貨単位 (例: 日本円なら円、米ドルならセント)」の Long で表す値クラス。
 *
 * BigDecimal をそのままドメイン内部で持ち回らないのは、丸め誤差や scale の扱いが
 * 呼び出し側ごとにブレる余地をなくすため。BigDecimal との変換点は
 * [fromBigDecimal] のスマートコンストラクタ1箇所に閉じ込める。
 */
@JvmInline
value class MoneyMinor private constructor(
    val value: Long,
) {
    companion object {
        val ZERO: MoneyMinor = MoneyMinor(0)

        fun create(value: Long): Either<ValidationError, MoneyMinor> =
            either {
                ensure(value >= 0) { ValueError.NegativeMoneyAmount(value) }
                MoneyMinor(value)
            }

        /**
         * BigDecimal (円やドルなどの「主単位」表記) を最小通貨単位に変換する。
         * 通貨ごとの小数桁数 ([Currency.getDefaultFractionDigits]) を使って
         * スケールを合わせ、割り切れない (= 最小単位未満の端数を持つ) 値は拒否する。
         */
        fun fromBigDecimal(
            major: BigDecimal,
            currency: Currency,
        ): Either<ValidationError, MoneyMinor> =
            either {
                val scale = currency.defaultFractionDigits.coerceAtLeast(0)
                val minorUnits = major.movePointRight(scale)
                val longValue =
                    Either
                        .catch { minorUnits.longValueExact() }
                        .mapLeft { ValueError.FractionalMinorUnit(major) }
                        .bind()
                create(longValue).bind()
            }
    }

    operator fun plus(other: MoneyMinor): Either<ValidationError, MoneyMinor> =
        either {
            val sum =
                Either
                    .catch { Math.addExact(value, other.value) }
                    .mapLeft { ValueError.MoneyAmountOverflow }
                    .bind()
            create(sum).bind()
        }

    fun multiply(factor: Long): Either<ValidationError, MoneyMinor> =
        either {
            val product =
                Either
                    .catch { Math.multiplyExact(value, factor) }
                    .mapLeft { ValueError.MoneyAmountOverflow }
                    .bind()
            create(product).bind()
        }
}
