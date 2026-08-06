package com.example.template.application.port

import arrow.core.Either
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.shared.OrderId

/**
 * Order 集約の永続化を担うアウトバウンドポート。実装は :adapter-persistence (R2DBC) が Chunk 3 で提供する。
 *
 * findById / save の2メソッドを持つため `fun interface` にはできない (SAM は単一メソッドのみ)。
 *
 * エラー型は専用の RepositoryError を新設せず、素の [OrderError] をそのまま使っている。
 * 理由: OrderError は sealed interface であり、直接の実装はこのインターフェースが宣言された
 * :domain モジュールの error パッケージ内でしかできない (Kotlin の言語制約)。つまり :application
 * 側でポート専用の新しいエラー型を作っても、それを OrderError と同じ「軸」で扱うことはできず、
 * 結局は呼び出し側で2つの異なるエラー階層を意識する二重管理になってしまう。
 * それよりも、ポートが表明したいエラー (「見つからない」「インフラ障害」) をあらかじめ
 * OrderError の変種として :domain 側に用意しておき ([OrderError.OrderNotFound] /
 * [OrderError.RepositoryUnavailable])、ポートはそれをそのまま返す方が、
 * ユースケース層は一貫して OrderError だけを見ればよくシンプルになる。
 *
 * 副作用として、findById の実装は理屈上 OrderNotFound / RepositoryUnavailable しか返さないが、
 * 型としては OrderError 全体 (InvalidOrderLine や InvalidTransition も含む) を公開する。
 * 呼び出し側で `when` を書く際は「実際には起こらないはずの分岐」も含めて網羅する必要があるが、
 * これは DomainError の2軸設計 (domain/error/DomainError.kt 参照) を素直に延長した結果であり、
 * 想定外の変種が来た場合にも安全側 (InfrastructureFailure 相当) に倒せるという利点がある。
 */
interface OrderRepository {
    /** 注文を ID で取得する。存在しない場合は [OrderError.OrderNotFound] を返す。 */
    suspend fun findById(id: OrderId): Either<OrderError, Order>

    /** 注文を作成または更新する (upsert)。永続化された結果 (実装によっては生成される付随情報を反映) を返す。 */
    suspend fun save(order: Order): Either<OrderError, Order>
}
