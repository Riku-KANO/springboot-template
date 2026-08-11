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
 * 1行でもパースできない場合は、黙って欠落させず [SettlementError.MalformedRecord] としてファイル
 * 全体を Left にする。ファイルそのものが取得できない場合は [SettlementError.InfrastructureFailure]。
 */
fun interface SettlementFilePort {
    suspend fun readRecordsFor(date: LocalDate): Either<SettlementError, List<SettlementRecord>>
}
