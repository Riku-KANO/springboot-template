package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.deliver
import com.example.template.domain.shared.OrderId
import java.time.Clock

data class DeliverOrderCommand(
    val orderId: OrderId,
)

/** 顧客への配達完了を記録する (Shipped -> Delivered) ユースケース。 */
fun interface DeliverOrder {
    suspend operator fun invoke(command: DeliverOrderCommand): Either<OrderError, Order>
}

/**
 * [DeliverOrder] の実装。ShipOrder / CancelOrder と同じ「読み込み → 保存」の
 * read-modify-write なので TxRunner で包む。
 *
 * deliveredAt は StartFulfillmentService の startedAt と同じ理由 (配達完了を確認した権威ある
 * 外部システムのポートが現状存在しない) で `java.time.Clock` から取る。将来、配送業者の
 * 配達確認 Webhook 等を受ける専用ポートを追加した場合はそちらの時刻を採用すべきで、
 * ここはあくまで現状の構成における妥当な選択。
 */
class DeliverOrderService(
    private val orderRepository: OrderRepository,
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemUTC(),
) : DeliverOrder {
    override suspend fun invoke(command: DeliverOrderCommand): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                val delivered = order.deliver(clock.instant()).bind()
                orderRepository.save(delivered).bind()
            }
        }
}
