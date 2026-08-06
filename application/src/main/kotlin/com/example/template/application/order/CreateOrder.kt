package com.example.template.application.order

import arrow.core.Either
import arrow.core.raise.either
import com.example.template.application.port.OrderRepository
import com.example.template.domain.error.OrderError
import com.example.template.domain.order.Order

/**
 * 新規注文を作成するユースケース。
 *
 * 入力の [CreateOrderCommand] は既に (companion object の `create` を通って) 検証済みなので、
 * ここではもう「フィールドが壊れていないか」を気にする必要はない。残る唯一の懸念は
 * 集約レベルの業務ルール (`Order.create` が担う重複 SKU チェック) であり、これはフェイルファストの
 * `Either<OrderError, Order>` で十分 (複数エラーを溜め込む理由がない) というのが domain 側の判断。
 * そのため戻り値は `EitherNel` ではなく通常の `Either<OrderError, Order>` にしている。
 */
fun interface CreateOrder {
    suspend operator fun invoke(command: CreateOrderCommand): Either<OrderError, Order>
}

/**
 * [CreateOrder] の実装。
 *
 * I/O は repository.save の1回だけであり、事前の読み込みを挟まない (真に新規の集約なので
 * 「読んでから書く」レースコンディションが存在しない)。そのため TxRunner でラップしていない。
 * 「複数ステップの I/O があるユースケースだけをトランザクション境界で包む」という方針は
 * PayOrder / ShipOrder / CancelOrder の実装コメントと合わせて参照。
 */
class CreateOrderService(
    private val orderRepository: OrderRepository,
) : CreateOrder {
    override suspend fun invoke(command: CreateOrderCommand): Either<OrderError, Order> =
        either {
            val order =
                Order
                    .create(command.orderId, command.customerId, command.lines, command.address)
                    .bind()
            orderRepository.save(order).bind()
        }
}
