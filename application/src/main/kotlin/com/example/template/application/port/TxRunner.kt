package com.example.template.application.port

import arrow.core.Either

/**
 * トランザクション境界を関数として表現したポート。
 *
 * なぜこの形が必要か: :application は Spring フリーであることが必須要件であり、Spring の
 * `TransactionalOperator` や `@Transactional` を直接参照することはできない。かといって
 * トランザクション境界そのものをユースケースから消してしまうと、「読み込み→外部連携→ドメイン遷移→
 * 保存」という複数ステップの整合性を誰が保証するのかが宙に浮いてしまう。そこで、
 * 「Either を返す suspend ブロックを受け取り、同じ形の Either を返す」という
 * 純粋に関数的なシグネチャだけをここで定義し、実装 (Chunk 3 で R2DBC の
 * `TransactionalOperator` をラップしたもの) を後続モジュールに委ねる。
 *
 * セマンティクス: block が [arrow.core.Either.Left] を返した場合、そのトランザクションは
 * ロールバックされなければならない (実装側の責務)。block が例外を投げた場合も同様にロールバック
 * 対象であるべきだが、:application 層のユースケースはそもそも例外を投げない設計 (Either で
 * 失敗を表現する) ため、実運用上ロールバックのトリガーはほぼ常に Left になる。
 *
 * `<E, A>` をインターフェース自身ではなくメソッド側の型パラメータにしているのは、1つの
 * TxRunner インスタンスを「注文用」「決済用」などエラー型ごとに使い分ける必要をなくすため。
 * ユースケースごとに異なる E (OrderError, SettlementError, ...) をそのまま透過的に扱える。
 *
 * `fun interface` (SAM) にはしていない: Kotlin は「抽象メソッドが自身の型パラメータを持つ
 * functional interface」を許可しない (`transactional` の `<E, A>` はインターフェースではなく
 * メソッド自身の型パラメータであり、あらゆる E・A に対応できる必要があるため、単一の
 * 関数型リテラルでは表現できない — 実際に `fun interface` で試すとコンパイルエラーになる)。
 * そのため実装はラムダではなく無名オブジェクト式 (`object : TxRunner { ... }`) で行う。
 */
interface TxRunner {
    suspend fun <E, A> transactional(block: suspend () -> Either<E, A>): Either<E, A>
}
