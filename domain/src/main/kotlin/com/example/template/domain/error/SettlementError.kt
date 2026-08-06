package com.example.template.domain.error

import com.example.template.domain.shared.OrderId

/** 決済プロバイダの日次消込ファイル (settlement file) に関するバウンデッドコンテキスト軸のエラー。 */
sealed interface SettlementError : DomainError {
    /** 消込ファイルの1行が読めない・形式が壊れている場合のエラー。 */
    data class MalformedRecord(
        val reason: String,
    ) : SettlementError,
        ValidationError {
        override val message: String = "malformed settlement record: $reason"
    }

    /** 消込ファイルが参照している orderId に対応する注文が見つからない場合のエラー。 */
    data class UnknownOrder(
        val orderId: OrderId,
    ) : SettlementError,
        NotFoundError {
        override val message: String = "settlement record references unknown order: ${orderId.value}"
    }

    /**
     * 消込処理を支える永続化層 (消込結果を保存する DB) や消込ファイルの取得元 (S3 等) との連携失敗。
     * :application 層の SettlementRepository / SettlementFilePort ポート実装から返される想定。
     * OrderError.RepositoryUnavailable と同じ理由 (sealed interface はモジュール外から直接実装できない)
     * で、:application 側が表明したいインフラ障害はこの SettlementError の変種として domain 側に持つ。
     */
    data class InfrastructureFailure(
        val cause: String,
    ) : SettlementError,
        InfrastructureError {
        override val message: String = "settlement infrastructure failure: $cause"
    }
}
