package com.example.template.application.settlement

import arrow.core.Either
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import java.time.Instant

/**
 * 消込バッチ (Chunk 5 の Spring Batch ジョブ) 1回分の実行結果サマリ。
 * [ReconcileSettlementRecord] を全レコードに適用した結果のリストから [summarize] で組み立てる。
 * このレポートは最終的に Chunk 6 のステートマシンへ [com.example.template.application.port.TaskCallbackPort]
 * 経由で渡され、[verdict] を見て次に分岐する (全件一致なら正常終了、不一致があれば人手確認フローへ、等)。
 */
data class SettlementReport(
    val processedAt: Instant,
    val matchedCount: Int,
    val mismatchCount: Int,
    val alreadySettledCount: Int,
    val notSettleableCount: Int,
    val failedCount: Int,
) {
    val totalCount: Int
        get() = matchedCount + mismatchCount + alreadySettledCount + notSettleableCount + failedCount

    /**
     * ステートマシンが分岐に使う3値の総合判定。failedCount > 0 を最優先で見るのは、
     * 「消込自体が実行できなかった」ことは金額不一致より深刻 (原因調査が先) という業務判断のため。
     */
    val verdict: SettlementVerdict
        get() =
            when {
                failedCount > 0 -> SettlementVerdict.Failed(failedCount)
                mismatchCount > 0 -> SettlementVerdict.HasDiscrepancies(mismatchCount)
                else -> SettlementVerdict.MatchedAll
            }

    companion object {
        /**
         * [ReconcileSettlementRecord] を各レコードに適用した結果のリストから、件数集計済みの
         * レポートを組み立てる。Left (SettlementError) は「消込処理自体が失敗した件数」として
         * failedCount に計上し、Right は [ReconciliationOutcome] の変種ごとに振り分ける。
         * ReconciliationOutcome の `when` に `else` を書いていないのは、domain 側の設計
         * (新しい変種が増えたらここも直させる) をそのまま踏襲するため。
         */
        fun summarize(
            processedAt: Instant,
            results: List<Either<SettlementError, ReconciliationOutcome>>,
        ): SettlementReport {
            var matched = 0
            var mismatch = 0
            var alreadySettled = 0
            var notSettleable = 0
            var failed = 0

            for (result in results) {
                when (result) {
                    is Either.Left -> failed++
                    is Either.Right ->
                        when (result.value) {
                            is ReconciliationOutcome.Matched -> matched++
                            is ReconciliationOutcome.AmountMismatch -> mismatch++
                            is ReconciliationOutcome.AlreadySettled -> alreadySettled++
                            is ReconciliationOutcome.OrderNotSettleable -> notSettleable++
                        }
                }
            }

            return SettlementReport(
                processedAt = processedAt,
                matchedCount = matched,
                mismatchCount = mismatch,
                alreadySettledCount = alreadySettled,
                notSettleableCount = notSettleable,
                failedCount = failed,
            )
        }
    }
}

/** [SettlementReport.verdict] が取りうる3通りの総合判定。Chunk 6 のステートマシンがここで分岐する。 */
sealed interface SettlementVerdict {
    /** 失敗もなく、金額不一致もなし。バッチは正常終了してよい。 */
    data object MatchedAll : SettlementVerdict

    /** 消込処理自体は完走したが、金額不一致が1件以上ある。人手確認フローへ回す。 */
    data class HasDiscrepancies(
        val mismatchCount: Int,
    ) : SettlementVerdict

    /** 消込処理自体が1件以上失敗した (注文が見つからない、インフラ障害等)。原因調査が必要。 */
    data class Failed(
        val failedCount: Int,
    ) : SettlementVerdict
}
