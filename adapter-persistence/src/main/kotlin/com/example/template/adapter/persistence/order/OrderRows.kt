package com.example.template.adapter.persistence.order

import com.example.template.domain.error.OrderError
import io.r2dbc.spi.Row
import java.time.Instant

/**
 * orders テーブル1行分の生データ。
 *
 * ドメインの [com.example.template.domain.order.Order] とは意図的に別の型にしている。
 * DB の行は「保存された時点ではスマートコンストラクタを通った正当な値のはず」だが、
 * 行というデータ形式そのものは理論上どんな文字列・数値も持ちうる。この2つを
 * 同じ型で扱ってしまうと「DB から読んだ直後の、まだ検証していない値」を
 * うっかり正当な Order として扱ってしまう事故を型で防げなくなる。
 * [toDomainOrder] がこの OrderRow を正当な Order へ変換する唯一の関門になる。
 */
internal data class OrderRow(
    val id: String,
    val customerId: String,
    val recipientName: String,
    val postalCode: String,
    val prefecture: String,
    val city: String,
    val addressLine1: String,
    val addressLine2: String?,
    val statusType: String,
    val statusPaidAt: Instant?,
    val statusFulfillingStartedAt: Instant?,
    val statusTrackingNumber: String?,
    val statusDeliveredAt: Instant?,
    val statusCancelledReason: String?,
    val statusRefundedAt: Instant?,
    val statusRefundedAmountMinor: Long?,
    val statusRefundedAmountCurrency: String?,
)

/** order_lines テーブル1行分の生データ。設計意図は [OrderRow] のコメントと同じ。 */
internal data class OrderLineRow(
    val lineNo: Int,
    val sku: String,
    val quantity: Int,
    val unitPriceMinor: Long,
    val unitPriceCurrency: String,
)

internal fun Row.toOrderRow(): OrderRow =
    OrderRow(
        id = requireString("id"),
        customerId = requireString("customer_id"),
        recipientName = requireString("recipient_name"),
        postalCode = requireString("postal_code"),
        prefecture = requireString("prefecture"),
        city = requireString("city"),
        addressLine1 = requireString("address_line1"),
        addressLine2 = get("address_line2", String::class.java),
        statusType = requireString("status_type"),
        statusPaidAt = get("status_paid_at", Instant::class.java),
        statusFulfillingStartedAt = get("status_fulfilling_started_at", Instant::class.java),
        statusTrackingNumber = get("status_tracking_number", String::class.java),
        statusDeliveredAt = get("status_delivered_at", Instant::class.java),
        statusCancelledReason = get("status_cancelled_reason", String::class.java),
        statusRefundedAt = get("status_refunded_at", Instant::class.java),
        statusRefundedAmountMinor = get("status_refunded_amount_minor", Long::class.javaObjectType),
        statusRefundedAmountCurrency = get("status_refunded_amount_currency", String::class.java),
    )

internal fun Row.toOrderLineRow(): OrderLineRow =
    OrderLineRow(
        lineNo = requireInt("line_no"),
        sku = requireString("sku"),
        quantity = requireInt("quantity"),
        unitPriceMinor = requireLong("unit_price_minor"),
        unitPriceCurrency = requireString("unit_price_currency"),
    )

/*
 * NOT NULL 制約のある列を読み出すための型別ヘルパー群。R2DBC の `Row.get` は API 上
 * つねに nullable を返すため、スキーマ上 NOT NULL のはずの列であっても Kotlin の型では
 * non-null を約束できない。ここで `null` だった場合は例外を投げるが、これは意図的:
 * 呼び出し元 ([OrderRepositoryAdapter]) は行の取得全体を `Either.catch { }` で包んでおり、
 * この例外はポート境界の外へ漏れる前に `OrderError.RepositoryUnavailable` へ変換される
 * (「スキーマ上ありえないはずの NULL が来た」というインフラ障害として扱う)。
 *
 * Int/Long 用に別関数を分けているのは、`reified T` で `T::class.java` を書くと
 * (T = Int/Long のときに) プリミティブの Class (`int.class`/`long.class`) に解決されてしまい、
 * ドライバによっては `Row.get` がプリミティブの Class トークンを受け付けない恐れがあるため。
 * `Int::class.javaObjectType` / `Long::class.javaObjectType` を明示することで、
 * 確実にボックス化された `java.lang.Integer`/`java.lang.Long` を渡す。
 */
private fun Row.requireString(name: String): String = get(name, String::class.java) ?: error("column '$name' was unexpectedly NULL")

private fun Row.requireInt(name: String): Int = get(name, Int::class.javaObjectType) ?: error("column '$name' was unexpectedly NULL")

private fun Row.requireLong(name: String): Long = get(name, Long::class.javaObjectType) ?: error("column '$name' was unexpectedly NULL")

internal fun corruptedOrderRow(
    orderId: String,
    reason: String,
): OrderError.RepositoryUnavailable = OrderError.RepositoryUnavailable("corrupted order row (id=$orderId): $reason")
