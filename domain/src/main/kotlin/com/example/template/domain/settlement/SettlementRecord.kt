package com.example.template.domain.settlement

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.domain.error.SettlementError
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import java.time.Instant
import java.util.Currency

/** 決済プロバイダの日次消込ファイルの1行を表すレコード。 */
data class SettlementRecord(
    val orderId: OrderId,
    val settledAmount: Money,
    val settledAt: Instant,
    val providerTransactionId: String,
) {
    companion object {
        /**
         * 消込ファイルの生の (文字列ベースの) 1行をパースする。
         * CSV 等から読んだ生データはどのフィールドも壊れている可能性があるため、
         * ここでは実行時例外 ([NumberFormatException] や [java.time.format.DateTimeParseException]) を
         * `Either.catch` でその場に閉じ込め、[SettlementError.MalformedRecord] に変換して外へは
         * 一切例外を漏らさない。
         */
        fun parse(
            rawOrderId: String,
            rawSettledAmountMinor: String,
            rawCurrency: String,
            rawSettledAt: String,
            rawProviderTransactionId: String,
        ): Either<SettlementError, SettlementRecord> =
            either {
                val orderId =
                    OrderId
                        .create(rawOrderId)
                        .mapLeft { SettlementError.MalformedRecord(it.message) }
                        .bind()

                val currency =
                    Either
                        .catch { Currency.getInstance(rawCurrency) }
                        .mapLeft { SettlementError.MalformedRecord("invalid currency code: $rawCurrency") }
                        .bind()

                val amountMinor = rawSettledAmountMinor.toLongOrNull()
                ensure(amountMinor != null) { SettlementError.MalformedRecord("invalid settled amount: $rawSettledAmountMinor") }
                val minor =
                    MoneyMinor
                        .create(amountMinor)
                        .mapLeft { SettlementError.MalformedRecord(it.message) }
                        .bind()

                val settledAt =
                    Either
                        .catch { Instant.parse(rawSettledAt) }
                        .mapLeft { SettlementError.MalformedRecord("invalid settled-at timestamp: $rawSettledAt") }
                        .bind()

                ensure(rawProviderTransactionId.isNotBlank()) {
                    SettlementError.MalformedRecord("provider transaction id must not be blank")
                }

                SettlementRecord(orderId, Money(minor, currency), settledAt, rawProviderTransactionId)
            }
    }
}
