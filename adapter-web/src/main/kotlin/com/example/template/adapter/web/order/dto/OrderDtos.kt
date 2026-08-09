package com.example.template.adapter.web.order.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Positive

/*
 * Web 境界の DTO 群。
 *
 * :domain / :application の型 (Order, OrderLine, CreateOrderCommand 等) を JSON として
 * 直接シリアライズ・デシリアライズしない。理由は大きく2つ:
 *
 * 1) ドメインの進化 (フィールド追加・sealed hierarchy への variant 追加・型の変更) が
 *    そのまま wire contract の破壊的変更になってしまう。DTO を1枚挟んでおけば、
 *    「ドメインの内部表現をどう変えるか」と「公開 API の後方互換性をどう保つか」を
 *    独立に意思決定できる。OrderStatus に新しい状態を追加しても、OrderDtoMapper が
 *    明示的に対応するまでレスポンスの形は変わらない (=勝手に変わらない)。
 * 2) OrderId / Sku などの @JvmInline value class や NonEmptyList を Jackson がどう
 *    シリアライズするかは実装都合に左右されやすい。DTO の平易な String/List フィールドで
 *    明示すれば、wire 上の形は常に安定する。
 *
 * ここでの @field:NotBlank / @field:Positive は実行時強制 (@Valid) のためではなく、
 * springdoc が OpenAPI スキーマに制約情報 (required, minimum 等) を反映するための
 * ドキュメンテーション目的で付与している。実際の入力検証は
 * CreateOrderCommand.create(...) の累積バリデーション (EitherNel) が一手に引き受ける。
 * これが :adapter-web モジュールのヘッドライン機能であり、Bean Validation の
 * フェイルファストな (かつ個々の @Valid 呼び出し単位でしか集約されない) 検証とは
 * 明確に役割分担している (詳細は OrderController.kt のコメントを参照)。
 */

data class CreateOrderRequest(
    @field:NotBlank val orderId: String,
    @field:NotBlank val customerId: String,
    @field:NotBlank val currency: String,
    val lines: List<OrderLineRequest>,
    val address: ShippingAddressRequest,
)

data class OrderLineRequest(
    @field:NotBlank val sku: String,
    @field:Positive val quantity: Int,
    val unitPriceMinor: Long,
)

data class ShippingAddressRequest(
    @field:NotBlank val recipientName: String,
    @field:NotBlank val postalCode: String,
    @field:NotBlank val prefecture: String,
    @field:NotBlank val city: String,
    @field:NotBlank val addressLine1: String,
    val addressLine2: String? = null,
)

data class ShipOrderRequest(
    @field:NotBlank val trackingNumber: String,
)

data class CancelOrderRequest(
    @field:NotBlank val reason: String,
)

/**
 * 返金金額。amountMinor (最小通貨単位) + currency (ISO-4217) のペアで、他の Money を
 * 受け渡す DTO (OrderLineRequest 等) と同じ表現に揃えている。ここでも実際の検証
 * (負の値でないか等) は MoneyMinor.create のスマートコンストラクタが一手に引き受けるため、
 * DTO 自体は素の primitive を持つだけでよい。
 */
data class RefundOrderRequest(
    val amountMinor: Long,
    @field:NotBlank val currency: String,
)

data class OrderResponse(
    val id: String,
    val customerId: String,
    val status: String,
    val lines: List<OrderLineResponse>,
    val address: ShippingAddressResponse,
    val version: Long,
)

data class OrderPageResponse(
    val items: List<OrderResponse>,
    val nextCursor: String?,
)

data class OrderLineResponse(
    val sku: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val currency: String,
)

data class ShippingAddressResponse(
    val recipientName: String,
    val postalCode: String,
    val prefecture: String,
    val city: String,
    val addressLine1: String,
    val addressLine2: String?,
)
