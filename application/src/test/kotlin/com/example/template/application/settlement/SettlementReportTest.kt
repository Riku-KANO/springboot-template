package com.example.template.application.settlement

import arrow.core.Either
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")
private val NOW: Instant = Instant.parse("2026-08-05T00:00:00Z")

class SettlementReportTest {
    @Test
    fun `全件一致なら MatchedAll になる`() {
        val results: List<Either<SettlementError, ReconciliationOutcome>> =
            listOf(Either.Right(ReconciliationOutcome.Matched), Either.Right(ReconciliationOutcome.Matched))

        val report = SettlementReport.summarize(NOW, results)

        assertEquals(2, report.matchedCount)
        assertEquals(0, report.mismatchCount)
        assertEquals(2, report.totalCount)
        assertEquals(SettlementVerdict.MatchedAll, report.verdict)
    }

    @Test
    fun `不一致が1件でもあれば HasDiscrepancies になる (失敗がない場合)`() {
        val mismatch =
            ReconciliationOutcome.AmountMismatch(
                expected = Money(MoneyMinor.create(1_000).shouldBeRight(), JPY),
                actual = Money(MoneyMinor.create(999).shouldBeRight(), JPY),
            )
        val results: List<Either<SettlementError, ReconciliationOutcome>> =
            listOf(Either.Right(ReconciliationOutcome.Matched), Either.Right(mismatch))

        val report = SettlementReport.summarize(NOW, results)

        assertEquals(1, report.matchedCount)
        assertEquals(1, report.mismatchCount)
        assertEquals(SettlementVerdict.HasDiscrepancies(1), report.verdict)
    }

    @Test
    fun `処理失敗が1件でもあれば 不一致より優先して Failed になる`() {
        val mismatch =
            ReconciliationOutcome.AmountMismatch(
                expected = Money(MoneyMinor.create(1_000).shouldBeRight(), JPY),
                actual = Money(MoneyMinor.create(999).shouldBeRight(), JPY),
            )
        val results: List<Either<SettlementError, ReconciliationOutcome>> =
            listOf(
                Either.Right(mismatch),
                Either.Left(SettlementError.UnknownOrder(OrderId.create("order-1").shouldBeRight())),
            )

        val report = SettlementReport.summarize(NOW, results)

        assertEquals(1, report.mismatchCount)
        assertEquals(1, report.failedCount)
        assertEquals(SettlementVerdict.Failed(1), report.verdict)
    }

    @Test
    fun `AlreadySettled と OrderNotSettleable もそれぞれ正しく集計される`() {
        val results: List<Either<SettlementError, ReconciliationOutcome>> =
            listOf(
                Either.Right(ReconciliationOutcome.AlreadySettled),
                Either.Right(ReconciliationOutcome.OrderNotSettleable(com.example.template.domain.order.OrderStatus.Draft)),
            )

        val report = SettlementReport.summarize(NOW, results)

        assertEquals(1, report.alreadySettledCount)
        assertEquals(1, report.notSettleableCount)
        assertEquals(SettlementVerdict.MatchedAll, report.verdict)
    }

    @Test
    fun `結果が空なら MatchedAll (異常なし) 扱いになる`() {
        val report = SettlementReport.summarize(NOW, emptyList())

        assertEquals(0, report.totalCount)
        assertEquals(SettlementVerdict.MatchedAll, report.verdict)
    }
}
