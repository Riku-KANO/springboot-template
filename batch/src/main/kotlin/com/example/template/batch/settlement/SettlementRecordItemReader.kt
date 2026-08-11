package com.example.template.batch.settlement

import com.example.template.application.port.SettlementFilePort
import com.example.template.domain.settlement.SettlementRecord
import kotlinx.coroutines.runBlocking
import org.springframework.batch.infrastructure.item.ExecutionContext
import org.springframework.batch.infrastructure.item.ItemStreamReader
import java.time.LocalDate

/**
 * 消込ファイルを読み込む ItemReader。
 *
 * ## なぜ FlatFileItemReader ではなく SettlementFilePort 経由なのか
 * Spring Batch には `FlatFileItemReader` という `Resource` から行単位で読み込む標準実装があるが、
 * それを使うと「S3 から消込ファイルを取得する」という詳細を :batch がドメイン知識として
 * 持たされてしまう (S3 の Resource 実装をどう組み立てるか、日付からキーをどう解決するか等)。
 * このテンプレートでは S3 の詳細を :adapter-messaging (S3SettlementFileAdapter) に閉じ込め、
 * :batch は :application が定義する [SettlementFilePort] というポートだけを知っていればよい設計に
 * している。:batch の build.gradle.kts のコメントにある通り :batch は :adapter-messaging に
 * 依存しないが、実際の Bean (S3 実装) は :bootstrap の ApplicationContext で解決される
 * (ちょうど :adapter-web の手動消込 API が同じ SettlementFilePort を経由しないのと対称的に、
 * バッチと Web のどちらも「消込ファイルの取得手段」を知らずに済む)。
 *
 * ## 実装方針
 * [SettlementFilePort.readRecordsFor] は「その日の全レコードを1回で返す」設計 (ストリーミングでは
 * ない) なので、この ItemReader は初回の read() 呼び出し時に1回だけポートを呼び、
 * 結果をメモリ上の Iterator として保持して1件ずつ払い出す (Spring Batch の `ListItemReader` と
 * 同種の「先読み一括ロード」パターン)。
 *
 * ファイル自体が読めない (SettlementError.InfrastructureFailure) という上位障害は、個々のレコードの
 * 失敗とは性質が異なり「ジョブ全体を続行する意味がない」ため、ここでは例外に変換して投げる
 * (ItemReader#read は checked Exception を許容するインターフェースであり、Spring Batch は
 * reader からの例外をステップの失敗として扱う。これは正しい挙動であり、個々のレコードの失敗を
 * Either で握りつぶさず skip-and-report する SettlementItemProcessor/Writer とは意図的に扱いを
 * 分けている)。
 *
 * runBlocking を使う理由は SettlementItemProcessor と同じ (ItemReader も同期 API のため)。
 */
class SettlementRecordItemReader(
    private val settlementFilePort: SettlementFilePort,
    private val settlementDate: LocalDate,
) : ItemStreamReader<SettlementRecord> {
    private var records: List<SettlementRecord> = emptyList()
    private var currentIndex: Int = 0

    override fun open(executionContext: ExecutionContext) {
        records = loadAll()
        currentIndex = executionContext.getInt(CURRENT_INDEX_KEY, 0)
    }

    override fun read(): SettlementRecord? = records.getOrNull(currentIndex)?.also { currentIndex++ }

    override fun update(executionContext: ExecutionContext) {
        executionContext.putInt(CURRENT_INDEX_KEY, currentIndex)
    }

    override fun close() {
        records = emptyList()
        currentIndex = 0
    }

    private fun loadAll(): List<SettlementRecord> =
        runBlocking {
            settlementFilePort
                .readRecordsFor(settlementDate)
                .fold(
                    { error -> throw SettlementFileUnreadableException(settlementDate, error.message) },
                    { it },
                )
        }

    companion object {
        private const val CURRENT_INDEX_KEY = "settlementRecordItemReader.currentIndex"
    }
}

/**
 * 消込ファイル自体が読み込めない場合に投げる、このモジュール内部だけで完結する例外。
 * ポート境界 (SettlementFilePort) の向こう側では Either として表現されているものを、
 * Spring Batch のステップ失敗として伝播させるためだけに一時的に例外化している。
 */
class SettlementFileUnreadableException(
    settlementDate: LocalDate,
    reason: String,
) : RuntimeException("failed to read settlement file for $settlementDate: $reason")
