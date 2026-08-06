package com.example.template.application.port

import arrow.core.Either
import com.example.template.domain.error.DomainError

/**
 * AWS Step Functions の `SendTaskSuccess` / `SendTaskFailure` を抽象化したアウトバウンドポート。
 * :application 層はこのポートの向こう側に AWS が居ることも、Step Functions という概念自体も知らない。
 * 実装は :adapter-messaging (AWS SDK) が後続チャンクで提供し、Chunk 6 のステートマシンが
 * `.waitForTaskToken` で払い出した [TaskToken] をここへ渡す。
 *
 * notifySuccess の payload を型パラメータにしているのは、settlement バッチ (Chunk 5) が返す
 * [com.example.template.application.settlement.SettlementReport] 専用にしてしまうと、
 * 将来的に他のバッチ/ワークフローがこのポートを再利用する際に settlement パッケージへの
 * 不要な依存が生まれてしまうため。実際の JSON シリアライズは :adapter-messaging の責務であり、
 * :application はペイロードの「型」だけを知っていればよい。
 *
 * notifyFailure は業務エラーを Step Functions へ伝播させるためのものなので、
 * :domain の [DomainError] をそのまま受け取る (HTTP マッピングと同様、カテゴリ軸だけで扱える)。
 */
interface TaskCallbackPort {
    suspend fun <A> notifySuccess(
        taskToken: TaskToken,
        payload: A,
    ): Either<TaskCallbackError, Unit>

    suspend fun notifyFailure(
        taskToken: TaskToken,
        error: DomainError,
    ): Either<TaskCallbackError, Unit>
}

/** Step Functions の `.waitForTaskToken` パターンでタスクに払い出されるトークン。 */
@JvmInline
value class TaskToken(
    val value: String,
)

/**
 * TaskCallbackPort 自体の呼び出し失敗 (Step Functions API 呼び出しの失敗等)。
 * これは業務エラーではなくコールバック機構そのものの障害なので、:domain の DomainError 階層には
 * 含めない (:adapter-web の HTTP マッピング対象にもならない、:batch 内部でのみ扱われる障害)。
 */
data class TaskCallbackError(
    val cause: String,
)
