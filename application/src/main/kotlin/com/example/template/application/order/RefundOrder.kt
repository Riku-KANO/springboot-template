package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.refund
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId
import java.time.Clock

/**
 * amount は生の Long/String ではなく、既に :domain のスマートコンストラクタ
 * (MoneyMinor.create 等) を通過済みの [Money] を受け取る。生プリミティブから Money への
 * 変換・検証は :adapter-web (呼び出し境界) の責務とし、:application はここでも
 * 「検証済みの値だけを扱う」という CreateOrderCommand と同じ方針を踏襲する。
 */
data class RefundOrderCommand(
    val orderId: OrderId,
    val amount: Money,
)

/** 注文を返金済みにする (Paid/Delivered -> Refunded) ユースケース。 */
fun interface RefundOrder {
    suspend operator fun invoke(command: RefundOrderCommand): Either<OrderError, Order>
}

/**
 * [RefundOrder] の実装。ShipOrder / CancelOrder と同じ「読み込み → 保存」の
 * read-modify-write なので TxRunner で包む。
 *
 * refundedAt は StartFulfillmentService / DeliverOrderService と同じ理由で `java.time.Clock`
 * から取る: 決済ゲートウェイに実際の返金 API を呼ぶ (与信取消等) 処理は本テンプレートの
 * PaymentGatewayPort にまだ存在しないため、現状は「返金が確定したことをアプリケーションが
 * 記録した時刻」として扱う。実際の決済プロバイダ連携を追加する際は PayOrderService の
 * paidAt (外部システムの権威に委ねる時刻) と同様の設計に寄せるのが筋。
 */
class RefundOrderService(
    private val orderRepository: OrderRepository,
    private val txRunner: TxRunner,
    private val clock: Clock = Clock.systemUTC(),
) : RefundOrder {
    override suspend fun invoke(command: RefundOrderCommand): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                val refunded = order.refund(clock.instant(), command.amount).bind()
                orderRepository.save(refunded).bind()
            }
        }
}
