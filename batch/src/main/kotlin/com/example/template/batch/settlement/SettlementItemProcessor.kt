package com.example.template.batch.settlement

import arrow.core.Either
import com.example.template.application.settlement.ReconcileSettlementRecord
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.SettlementRecord
import kotlinx.coroutines.runBlocking
import org.springframework.batch.infrastructure.item.ItemProcessor
import org.springframework.stereotype.Component

/**
 * 消込ファイルの1レコードを判定する ItemProcessor。
 *
 * 判定ロジック自体は一切ここに書かない。:application の [ReconcileSettlementRecord] ユースケース
 * (内部で :domain の純粋関数 `reconcile` を呼び、結果を SettlementRepository 経由で永続化する) を
 * そのまま呼び出すだけであり、これは :adapter-web の手動消込 API と全く同じユースケースの再利用
 * (ReconcileSettlementRecord.kt の KDoc 参照)。バッチ経路と Web 経路とで判定ロジックが
 * ズレることはあり得ない設計になっている。
 *
 * ## runBlocking を使う理由 (意図的なトレードオフ)
 * Spring Batch 6 の ItemProcessor は `process(item: I): O` という同期 (blocking) シグネチャであり、
 * suspend 関数ではない。一方 :application のポート/ユースケースは coroutine ベース (suspend fun) で
 * 設計されている (将来の非同期 I/O 差し替えやテスト容易性のため)。この2つを繋ぐには
 * runBlocking で同期呼び出しに変換するしかない。
 * 代替案として「バッチ専用に ReconcileSettlementRecord の同期版を作る」ことも考えられるが、
 * それは同じ業務判断ロジックを2箇所に複製することを意味し、判定ロジックの二重管理という
 * より大きな問題を生む。Spring Batch のステップ実行スレッドは (WebFlux のイベントループのような)
 * 共有リアクティブスレッドプールではなく、チャンク処理専用のスレッドであるため、
 * ここで一時的にブロックしてもアプリケーション全体のスループットには影響しない。
 * これらを踏まえ「読みやすさとロジックの一元化」を優先し runBlocking を採用している。
 *
 * 戻り値を `Either<SettlementError, ReconciledSettlement>` (never null, never throw) にしているため、
 * Spring Batch 自体のスキップ/リトライ機構 (SkipPolicy 等) を一切使わずに
 * 「レコード単位の失敗でジョブ全体を止めない」を実現できる。実際の分岐は SettlementItemWriter が行う。
 */
@Component
class SettlementItemProcessor(
    private val reconcileSettlementRecord: ReconcileSettlementRecord,
) : ItemProcessor<SettlementRecord, Either<SettlementError, ReconciledSettlement>> {
    override fun process(item: SettlementRecord): Either<SettlementError, ReconciledSettlement> =
        runBlocking {
            reconcileSettlementRecord(item).map { outcome -> ReconciledSettlement(item, outcome) }
        }
}
