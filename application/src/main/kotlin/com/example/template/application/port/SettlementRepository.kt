package com.example.template.application.port

import arrow.core.Either
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import java.time.Instant

/**
 * 消込結果の永続化を担うアウトバウンドポート。実装は :adapter-persistence が Chunk 3 で提供する。
 *
 * メソッドが1つだけなので `fun interface` にできる。エラー型の設計方針は [OrderRepository] の
 * KDoc を参照 ([SettlementError.InfrastructureFailure] を再利用する)。
 *
 * recordedAt を呼び出し側から受け取る形にしているのは、「この消込結果をいつ確定させたか」が
 * 業務上の監査対象になりうるため。ポート実装側の Clock ではなく、ユースケース側で
 * (java.time.Clock を注入して) 決めた時刻を渡す設計にすることで、ポートの実装を
 * 差し替えてもテスト時刻の決定ロジックは変わらない。
 */
fun interface SettlementRepository {
    suspend fun recordOutcome(
        record: SettlementRecord,
        outcome: ReconciliationOutcome,
        recordedAt: Instant,
    ): Either<SettlementError, Unit>
}
