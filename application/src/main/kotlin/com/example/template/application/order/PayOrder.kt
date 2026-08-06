package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.PaymentGatewayPort
import com.example.template.application.port.TxRunner
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order
import com.example.template.domain.order.submitPayment
import com.example.template.domain.shared.OrderId

data class PayOrderCommand(
    val orderId: OrderId,
)

/**
 * 注文の決済を確定するユースケース。
 * 「注文を読む → 決済ゲートウェイに請求する → submitPayment 遷移を適用する → 保存する」という
 * 複数ステップの中で判断を下すのは domain (`Order.total` / `submitPayment`) だけであり、
 * このユースケース自体は各ステップをオーケストレーションするだけでビジネスルールを持たない。
 */
fun interface PayOrder {
    suspend operator fun invoke(command: PayOrderCommand): Either<OrderError, Order>
}

/**
 * [PayOrder] の実装。
 *
 * 「読み込み (findById) → 外部連携 (charge) → 保存 (save)」という3つの I/O ステップを踏むため、
 * TxRunner でラップする。途中の decode (charge や submitPayment) が Left を返した場合、
 * either ブロックはその時点で計算を打ち切り、save は決して呼ばれない
 * (PayOrderServiceTest の「決済失敗時は save を呼ばない」テストで検証する)。
 * TxRunner.transactional に渡した either ブロックが最終的に Left を返せば、実装
 * (Chunk 3 の R2DBC TxRunner) 側でトランザクション全体がロールバックされる。
 */
class PayOrderService(
    private val orderRepository: OrderRepository,
    private val paymentGatewayPort: PaymentGatewayPort,
    private val txRunner: TxRunner,
) : PayOrder {
    override suspend fun invoke(command: PayOrderCommand): Either<OrderError, Order> =
        txRunner.transactional {
            either {
                val order = orderRepository.findById(command.orderId).bind()
                // total() は ValidationError を返すが、Money の算術オーバーフロー等は集約レベルの
                // データ不整合として扱ってよいため、OrderError.InvalidOrderLine に畳み込む。
                val total = order.total().mapLeft { OrderError.InvalidOrderLine(it.message) }.bind()
                val charge = paymentGatewayPort.charge(order.id, total).bind()
                val paid = order.submitPayment(charge.paidAt).bind()
                orderRepository.save(paid).bind()
            }
        }
}
