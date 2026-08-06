package com.example.template.domain.error

import java.math.BigDecimal
import java.util.Currency

/**
 * このファイルの各値クラス (OrderId, CustomerId, Sku, Quantity, MoneyMinor, Money) の
 * スマートコンストラクタが返すバウンデッドコンテキスト軸のエラー。
 *
 * 値クラスの構築失敗は「常に」入力値バリデーションの失敗なので、全メンバーが
 * [ValidationError] を実装する。それでも [OrderError] 等と同じく、
 * バウンデッドコンテキスト軸 ([ValueError]) とカテゴリ軸 ([ValidationError]) を
 * それぞれ独立したインターフェースとして個々のメンバーに実装させているのは、
 * 「値オブジェクトのエラーだから ValidationError 固定」という特殊ルールを
 * ADT の形に持ち込まず、[DomainError] 全体で一貫した2軸パターンを保つため。
 */
sealed interface ValueError : DomainError {
    data object BlankOrderId : ValueError, ValidationError {
        override val message: String = "OrderId must not be blank"
    }

    data class OrderIdTooLong(
        val length: Int,
    ) : ValueError,
        ValidationError {
        override val message: String = "OrderId length $length exceeds maximum of $MAX_ID_LENGTH"
    }

    data object BlankCustomerId : ValueError, ValidationError {
        override val message: String = "CustomerId must not be blank"
    }

    data class CustomerIdTooLong(
        val length: Int,
    ) : ValueError,
        ValidationError {
        override val message: String = "CustomerId length $length exceeds maximum of $MAX_ID_LENGTH"
    }

    data class InvalidSkuFormat(
        val raw: String,
    ) : ValueError,
        ValidationError {
        override val message: String = "'$raw' is not a valid SKU (expected [A-Z0-9-]{1,32})"
    }

    data class NonPositiveQuantity(
        val raw: Int,
    ) : ValueError,
        ValidationError {
        override val message: String = "Quantity must be positive, but was $raw"
    }

    data class QuantityTooLarge(
        val raw: Int,
    ) : ValueError,
        ValidationError {
        override val message: String = "Quantity $raw exceeds maximum of $MAX_QUANTITY"
    }

    data class NegativeMoneyAmount(
        val raw: Long,
    ) : ValueError,
        ValidationError {
        override val message: String = "Money amount must not be negative, but was $raw"
    }

    data object MoneyAmountOverflow : ValueError, ValidationError {
        override val message: String = "Money amount overflowed Long during arithmetic"
    }

    data class FractionalMinorUnit(
        val raw: BigDecimal,
    ) : ValueError,
        ValidationError {
        override val message: String = "'$raw' cannot be represented as an integral number of minor units"
    }

    data class CurrencyMismatch(
        val expected: Currency,
        val actual: Currency,
    ) : ValueError,
        ValidationError {
        override val message: String =
            "currency mismatch: expected ${expected.currencyCode} but was ${actual.currencyCode}"
    }

    companion object {
        const val MAX_ID_LENGTH: Int = 64
        const val MAX_QUANTITY: Int = 9_999
    }
}
