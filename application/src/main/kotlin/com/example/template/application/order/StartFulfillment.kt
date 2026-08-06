package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.startFulfilling
import com.example.template.domain.shared.OrderId
import java.time.Clock

data class StartFulfillmentCommand(
    val orderId: OrderId,
)

/** 倉庫でのピッキング・梱包着手を記録する (Paid -> Fulfilling) ユースケース。 */
fun interface StartFulfillment {
    suspend operator fun invoke(command: StartFulfillmentCommand): Either<OrderError, Order>
}

/**
 * [StartFulfillment] の実装。ShipOrder / CancelOrder と同じ「読み込み → 保存」の
 * read-modify-write なので TxRunner で包む点は変わらない。
 *
 * startedAt の出処だけが他のユースケースと違う: PayOrder の paidAt は決済プロバイダという
 * 外部システムの権威に委ねるべき時刻だが、こちらは「倉庫作業への着手をアプリケーションが
 * 検知した時刻」であり、権威を持つ外部システムが存在しない。ReconcileSettlementService の
 * recordedAt と同じ理由により、専用ポートを新設せず `java.time.Clock` (テストでは
 * Clock.fixed に差し替え可能) をそのまま使う。
 */
class StartFulfillmentService(
    private val orderRepository: OrderRepository,
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemUTC(),
) : StartFulfillment {
    override suspend fun invoke(command: StartFulfillmentCommand): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                val fulfilling = order.startFulfilling(clock.instant()).bind()
                orderRepository.save(fulfilling).bind()
            }
        }
}
