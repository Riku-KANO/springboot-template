package com.example.template.adapter.web.order

import com.example.template.adapter.web.order.dto.OrderLineRequest
import com.example.template.adapter.web.order.dto.OrderLineResponse
import com.example.template.adapter.web.order.dto.OrderResponse
import com.example.template.adapter.web.order.dto.ShippingAddressRequest
import com.example.template.adapter.web.order.dto.ShippingAddressResponse
import com.example.template.application.order.RawOrderLine
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.ShippingAddress

/**
 * DTO <-> ドメイン型の相互変換をここに閉じ込める。
 * OrderController がこのファイルの外で個別に "id.value" のようなアクセスをしなくて
 * 済むようにし、DTO の形が変わる際の影響範囲をこのファイルだけに限定する。
 */

fun ShippingAddressRequest.toDomain(): ShippingAddress =
    ShippingAddress(recipientName, postalCode, prefecture, city, addressLine1, addressLine2)

fun OrderLineRequest.toRaw(): RawOrderLine = RawOrderLine(sku, quantity, unitPriceMinor)

fun ShippingAddress.toResponse(): ShippingAddressResponse =
    ShippingAddressResponse(recipientName, postalCode, prefecture, city, addressLine1, addressLine2)

fun OrderLine.toResponse(): OrderLineResponse =
    OrderLineResponse(sku.value, quantity.value, unitPrice.amount.value, unitPrice.currency.currencyCode)

fun Order.toResponse(): OrderResponse =
    OrderResponse(
        id = id.value,
        customerId = customerId.value,
        status = status.toLabel(),
        lines = lines.map { it.toResponse() },
        address = address.toResponse(),
    )

/**
 * OrderStatus -> wire 上のラベル文字列。
 *
 * else を書かない exhaustive when にしているのは、:domain/order/OrderTransitions.kt の
 * 「7つの遷移関数すべてに else を書かない」設計判断をこの境界でも踏襲するため。
 * OrderStatus に新しい状態 (例: "返品受付中") が追加された瞬間、遷移関数群だけでなく
 * この wire 表現の変換もコンパイルエラーになり、「レスポンスにどう出すか決め忘れる」
 * バグを構造的に防ぐ。paidAt や trackingNumber のような各状態固有の付随情報は
 * 現時点の DTO 契約では意図的に含めていない (状態ラベルのみを公開する、という
 * 最小の wire contract を選択した)。
 */
private fun OrderStatus.toLabel(): String =
    when (this) {
        is OrderStatus.Draft -> "DRAFT"
        is OrderStatus.PendingPayment -> "PENDING_PAYMENT"
        is OrderStatus.Paid -> "PAID"
        is OrderStatus.Fulfilling -> "FULFILLING"
        is OrderStatus.Shipped -> "SHIPPED"
        is OrderStatus.Delivered -> "DELIVERED"
        is OrderStatus.Cancelled -> "CANCELLED"
        is OrderStatus.Refunded -> "REFUNDED"
    }
