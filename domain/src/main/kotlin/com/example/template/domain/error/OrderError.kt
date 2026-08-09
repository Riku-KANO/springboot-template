package com.example.template.domain.error

import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.OrderId

/**
 * Order 集約に関するバウンデッドコンテキスト軸のエラー。
 * 各メンバーはこのインターフェースに加えて、HTTP マッピング用のカテゴリマーカー
 * ([ValidationError] / [NotFoundError] / [ConflictError] / [InfrastructureError]) を
 * それぞれ異なる形で実装している点に注目。同じ OrderError でも扱いはバラバラ、というのが
 * この2軸設計の要点 ([DomainError] のコメントを参照)。
 *
 * なお、Kotlin の sealed interface は「直接の実装/継承先は同じパッケージに置く」
 * という制約があるため、OrderError 自体は (概念上は order/ に近くても) この
 * error パッケージに置いている。ValueError・SettlementError も同様の理由でここにある。
 */
sealed interface OrderError : DomainError {
    /** OrderLine 単体のフィールドバリデーションではなく、集約レベルの業務ルール違反 (例: SKU 重複)。 */
    data class InvalidOrderLine(
        val reason: String,
    ) : OrderError,
        ValidationError {
        override val message: String = "invalid order line: $reason"
    }

    data class OrderNotFound(
        val orderId: OrderId,
    ) : OrderError,
        NotFoundError {
        override val message: String = "order not found: ${orderId.value}"
    }

    /** 現在の [OrderStatus] からは許可されていない遷移が試みられたことを表す。 */
    data class InvalidTransition(
        val from: OrderStatus,
        val attempted: String,
    ) : OrderError,
        ConflictError {
        override val message: String = "cannot perform '$attempted' while order is in status $from"
    }

    data class ConcurrentModification(
        val orderId: OrderId,
    ) : OrderError,
        ConflictError {
        override val message: String = "order was modified concurrently: ${orderId.value}"
    }

    /** 決済ゲートウェイ等、外部インフラとの連携失敗。:application 層のポート実装から返される想定。 */
    data class PaymentGatewayUnavailable(
        val cause: String,
    ) : OrderError,
        InfrastructureError {
        override val message: String = "payment gateway unavailable: $cause"
    }

    /**
     * 永続化層 (R2DBC 等) との連携失敗。:application 層の OrderRepository ポート実装から返される想定。
     *
     * OrderRepository は findById/save の両方とも Either<OrderError, ...> を返す設計にしている
     * (専用の RepositoryError 型を新設していない)。sealed interface である OrderError は
     * このモジュール外からは直接実装できない (Kotlin の「直接の実装先は同じパッケージ」制約) ため、
     * :application 側のポートが表明したいエラー (「見つからない」「インフラ障害」) は
     * 最終的に必ずこの OrderError の変種として domain 側で定義しておく必要がある。
     * PaymentGatewayUnavailable と対になる、リポジトリ版のインフラ障害がこれ。
     */
    data class RepositoryUnavailable(
        val cause: String,
    ) : OrderError,
        InfrastructureError {
        override val message: String = "order repository unavailable: $cause"
    }
}
