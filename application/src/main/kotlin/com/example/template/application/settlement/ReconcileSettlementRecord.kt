package com.example.template.application.settlement

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.SettlementRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import com.example.template.domain.settlement.reconcile
import java.time.Clock

/**
 * 消込ファイルの1レコードを注文と突き合わせるユースケース。
 *
 * このユースケースが :batch (Chunk 5 の Spring Batch ItemProcessor) と :adapter-web
 * (手動消込 API) の両方から呼ばれることを意図的に想定している。判定ロジック自体は
 * `reconcile` (domain の純粋関数) にあり、このユースケースは「注文を読み込む」「結果を保存する」
 * という I/O のオーケストレーションだけを担う。バッチと Web で同じユースケースを再利用できるのは、
 * ユースケース層がポート (インターフェース) にしか依存せず、I/O の具体的な手段
 * (JDBC バッチ実行か、単発の R2DBC クエリか) を知らないため。
 */
fun interface ReconcileSettlementRecord {
    suspend operator fun invoke(record: SettlementRecord): Either<SettlementError, ReconciliationOutcome>
}

/**
 * [ReconcileSettlementRecord] の実装。
 *
 * 「注文を読み込む → 判定する → 結果を保存する」の2 I/O ステップがあるため TxRunner で包む。
 *
 * orderRepository.findById は Either<OrderError, Order> を返すが、このユースケースの戻り値は
 * Either<SettlementError, ...> なので、OrderError を SettlementError へ変換する必要がある。
 * ここで `when` に `else` を書かず全変種を網羅しているのは、OrderError が将来増えたときに
 * このマッピング漏れをコンパイルエラーとして検出したいため (domain/order/OrderTransitions.kt と
 * 同じ考え方)。findById の実装は理屈上 OrderNotFound / RepositoryUnavailable しか返さないが、
 * 型としては OrderError 全体を受け取るので、それ以外の変種は
 * (本来起こらないはずの分岐として) InfrastructureFailure に安全側で倒す。
 *
 * recordedAt に `java.time.Clock` を使っているのは、これが「アプリケーションが実際にこの消込を
 * 処理した時刻」という、外部システムの権威に委ねるべきではない (submitPayment の paidAt とは違う)
 * 情報だから。java.time.Clock は JDK 標準のテスト可能な時刻抽象 (Clock.fixed でテストから
 * 固定できる) であり、これをそのまま使えば十分で、独自の Clock ポートを新設する意味がない。
 */
class ReconcileSettlementService(
    private val orderRepository: OrderRepository,
    private val settlementRepository: SettlementRepository,
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemUTC(),
) : ReconcileSettlementRecord {
    override suspend fun invoke(record: SettlementRecord): Either<SettlementError, ReconciliationOutcome> =
        txRunner.transactional {
            either {
                val order =
                    orderRepository
                        .findById(record.orderId)
                        .mapLeft { it.toSettlementError(record) }
                        .bind()
                val outcome = reconcile(order, record).bind()
                settlementRepository.recordOutcome(record, outcome, clock.instant()).bind()
                outcome
            }
        }

    private fun OrderError.toSettlementError(record: SettlementRecord): SettlementError =
        when (this) {
            is OrderError.OrderNotFound -> SettlementError.UnknownOrder(record.orderId)
            is OrderError.RepositoryUnavailable -> SettlementError.InfrastructureFailure(cause)
            is OrderError.InvalidOrderLine -> SettlementError.InfrastructureFailure(message)
            is OrderError.InvalidTransition -> SettlementError.InfrastructureFailure(message)
            is OrderError.ConcurrentModification -> SettlementError.InfrastructureFailure(message)
            is OrderError.PaymentGatewayUnavailable -> SettlementError.InfrastructureFailure(message)
        }
}
