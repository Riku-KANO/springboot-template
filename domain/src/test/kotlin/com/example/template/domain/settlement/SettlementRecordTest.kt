package com.example.template.domain.settlement

import com.example.template.domain.error.SettlementError
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** SettlementRecord.parse の成功・失敗パスを検証する。 */
class SettlementRecordTest {
    @Test
    fun `parse は全フィールドが有効なら SettlementRecord を作る`() {
        val record =
            SettlementRecord
                .parse(
                    rawOrderId = "order-1",
                    rawSettledAmountMinor = "1000",
                    rawCurrency = "JPY",
                    rawSettledAt = "2026-01-01T00:00:00Z",
                    rawProviderTransactionId = "txn-1",
                ).shouldBeRight()

        assertEquals("order-1", record.orderId.value)
        assertEquals(1000L, record.settledAmount.amount.value)
        assertEquals("txn-1", record.providerTransactionId)
    }

    @Test
    fun `parse は数値でない金額を MalformedRecord として拒否する`() {
        SettlementRecord
            .parse(
                rawOrderId = "order-1",
                rawSettledAmountMinor = "not-a-number",
                rawCurrency = "JPY",
                rawSettledAt = "2026-01-01T00:00:00Z",
                rawProviderTransactionId = "txn-1",
            ).shouldBeLeftOfType<SettlementError.MalformedRecord>()
    }

    @Test
    fun `parse は不正な通貨コードを MalformedRecord として拒否する`() {
        SettlementRecord
            .parse(
                rawOrderId = "order-1",
                rawSettledAmountMinor = "1000",
                rawCurrency = "NOT_A_CURRENCY",
                rawSettledAt = "2026-01-01T00:00:00Z",
                rawProviderTransactionId = "txn-1",
            ).shouldBeLeftOfType<SettlementError.MalformedRecord>()
    }

    @Test
    fun `parse は不正なタイムスタンプを MalformedRecord として拒否する`() {
        SettlementRecord
            .parse(
                rawOrderId = "order-1",
                rawSettledAmountMinor = "1000",
                rawCurrency = "JPY",
                rawSettledAt = "not-a-timestamp",
                rawProviderTransactionId = "txn-1",
            ).shouldBeLeftOfType<SettlementError.MalformedRecord>()
    }

    @Test
    fun `parse は空の provider transaction id を MalformedRecord として拒否する`() {
        SettlementRecord
            .parse(
                rawOrderId = "order-1",
                rawSettledAmountMinor = "1000",
                rawCurrency = "JPY",
                rawSettledAt = "2026-01-01T00:00:00Z",
                rawProviderTransactionId = "   ",
            ).shouldBeLeftOfType<SettlementError.MalformedRecord>()
    }
}
