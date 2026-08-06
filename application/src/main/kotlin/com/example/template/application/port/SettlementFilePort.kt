package com.example.template.application.port

import arrow.core.Either
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.SettlementRecord
import java.time.LocalDate

/**
 * 決済プロバイダが日次で払い出す消込ファイルを読み取るアウトバウンドポート。実装は
 * :adapter-persistence or 専用アダプタが S3 からの取得を担う (Chunk 3/5)。:application は
 * 「日付を渡すとレコードの一覧が返ってくる」ことしか知らず、S3 やファイルフォーマットの存在は知らない。
 *
 * 1行単位のパース失敗 ([SettlementError.MalformedRecord]) は [SettlementRecord.parse] が既に
 * 個々のレコード単位の Either として表現しているため、このポートは「アダプタが実際に読めた行だけ」を
 * List で返す設計にしている。ファイルそのものが取得できない (S3 オブジェクトが無い等) といった
 * より上位の障害は [SettlementError.InfrastructureFailure] として Left で返す。
 */
fun interface SettlementFilePort {
    suspend fun readRecordsFor(date: LocalDate): Either<SettlementError, List<SettlementRecord>>
}
