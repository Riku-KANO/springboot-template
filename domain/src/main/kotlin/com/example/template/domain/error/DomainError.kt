package com.example.template.domain.error

/**
 * ドメイン全体のエラーを表す最上位の型。
 *
 * この ADT は意図的に「2軸」で設計されている。
 *
 * 軸1 (バウンデッドコンテキスト軸): [com.example.template.domain.order.OrderError],
 * [com.example.template.domain.shared.ValueError],
 * [com.example.template.domain.settlement.SettlementError] のように、
 * どの業務領域で起きたエラーかを表す sealed interface。
 * ユースケース層のビジネスロジックはこの軸で `when` 分岐し、
 * 「注文が見つからない場合は在庫を戻す」のような業務判断を行う。
 *
 * 軸2 (カテゴリ軸): [ValidationError] / [NotFoundError] / [ConflictError] /
 * [InfrastructureError] という、HTTP ステータスコードや監視上の扱いに
 * 直結するマーカー。:adapter-web 層はこの軸だけで `when` 分岐し、
 * 「404 を返すか 409 を返すか」を決める。個々のエラーの中身
 * (OrderNotFound か SettlementError.UnknownOrder か) を知る必要はない。
 *
 * 具象エラークラスは必ずこの両方の軸のインターフェースを実装する
 * (例: `OrderError.OrderNotFound : OrderError, NotFoundError`)。
 * 1つの sealed interface に両方の役割を持たせず軸を分離しているのは、
 * 「同じバウンデッドコンテキストのエラーでもカテゴリはバラバラになりうる」
 * ため (InvalidTransition は Conflict だが InvalidOrderLine は Validation、など)。
 * 軸を1つにまとめてしまうと、この非対称性を表現できなくなる。
 */
sealed interface DomainError {
    val message: String
}

/** 入力値が不正であることを表すカテゴリ (HTTP 400 相当)。 */
sealed interface ValidationError : DomainError

/** 対象のリソースが存在しないことを表すカテゴリ (HTTP 404 相当)。 */
sealed interface NotFoundError : DomainError

/** 現在の状態と矛盾する操作が要求されたことを表すカテゴリ (HTTP 409 相当)。 */
sealed interface ConflictError : DomainError

/** 外部システム(決済ゲートウェイ等)との連携で問題が起きたことを表すカテゴリ (HTTP 502/503 相当)。 */
sealed interface InfrastructureError : DomainError
