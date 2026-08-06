package com.example.template.domain.testfixtures

import arrow.core.NonEmptyList
import com.example.template.domain.error.ValueError
import com.example.template.domain.order.Order
import com.example.template.domain.order.OrderLine
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.Quantity
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.shared.Sku
import io.kotest.property.Arb
import io.kotest.property.arbitrary.bind
import io.kotest.property.arbitrary.choice
import io.kotest.property.arbitrary.constant
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.javaInstant
import io.kotest.property.arbitrary.list
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.map
import io.kotest.property.arbitrary.orNull
import io.kotest.property.arbitrary.string
import java.time.Instant
import java.util.Currency

/**
 * このモジュール (と後続モジュール) のプロパティテストで使い回す Arb 定義。
 *
 * すべてスマートコンストラクタ ( `XXX.create(...)` ) を通して値を組み立てている。
 * これにより「Arb が生成する値は常にスマートコンストラクタが受理する = 常に有効」
 * という不変条件がテストコード側でも保たれる。万一スマートコンストラクタが
 * Left を返した場合は [shouldBeRight] がその場でテストを失敗させるので、
 * 「Arb の実装ミスで無効な値を混入させてしまう」バグにもすぐ気づける。
 */

private val ALPHANUMERIC = ('a'..'z') + ('A'..'Z') + ('0'..'9')
private val SKU_CHARS = ALPHANUMERIC + '-'
private val TEST_CURRENCIES = listOf("JPY", "USD", "EUR").map(Currency::getInstance)
private val TEST_INSTANT_RANGE = Instant.parse("2020-01-01T00:00:00Z")..Instant.parse("2030-01-01T00:00:00Z")

fun Arb.Companion.testInstant(): Arb<Instant> = Arb.javaInstant(TEST_INSTANT_RANGE.start, TEST_INSTANT_RANGE.endInclusive)

fun Arb.Companion.orderId(): Arb<OrderId> = Arb.string(1..20, ALPHANUMERIC.joinToString("")).map { OrderId.create(it).shouldBeRight() }

fun Arb.Companion.customerId(): Arb<CustomerId> =
    Arb.string(1..20, ALPHANUMERIC.joinToString("")).map { CustomerId.create(it).shouldBeRight() }

fun Arb.Companion.sku(): Arb<Sku> = Arb.string(1..16, SKU_CHARS.joinToString("")).map { Sku.create(it).shouldBeRight() }

fun Arb.Companion.quantity(): Arb<Quantity> = Arb.int(1..ValueError.MAX_QUANTITY).map { Quantity.create(it).shouldBeRight() }

fun Arb.Companion.moneyMinor(): Arb<MoneyMinor> = Arb.long(0L..1_000_000_000L).map { MoneyMinor.create(it).shouldBeRight() }

fun Arb.Companion.testCurrency(): Arb<Currency> = Arb.element(TEST_CURRENCIES)

fun Arb.Companion.money(): Arb<Money> = Arb.bind(Arb.moneyMinor(), Arb.testCurrency()) { minor, currency -> Money(minor, currency) }

fun Arb.Companion.testPostalCode(): Arb<String> =
    Arb.int(0..9_999_999).map { n ->
        val digits = n.toString().padStart(7, '0')
        "${digits.take(3)}-${digits.takeLast(4)}"
    }

fun Arb.Companion.shippingAddress(): Arb<ShippingAddress> =
    Arb.bind(
        Arb.string(1..10, ALPHANUMERIC.joinToString("")),
        Arb.testPostalCode(),
        Arb.string(1..10, ALPHANUMERIC.joinToString("")),
        Arb.string(1..10, ALPHANUMERIC.joinToString("")),
        Arb.string(1..20, ALPHANUMERIC.joinToString("")),
        Arb.string(1..20, ALPHANUMERIC.joinToString("")).orNull(),
    ) { recipientName, postalCode, prefecture, city, addressLine1, addressLine2 ->
        ShippingAddress(recipientName, postalCode, prefecture, city, addressLine1, addressLine2)
    }

fun Arb.Companion.orderLine(): Arb<OrderLine> =
    Arb.bind(Arb.sku(), Arb.quantity(), Arb.money()) { sku, quantity, money -> OrderLine(sku, quantity, money) }

/** 1件以上5件以下の OrderLine からなる NonEmptyList。null 非許容の unwrap を避けるため head/tail で直接組み立てる。 */
fun Arb.Companion.orderLines(): Arb<NonEmptyList<OrderLine>> =
    Arb.bind(Arb.orderLine(), Arb.list(Arb.orderLine(), 0..4)) { head, tail -> NonEmptyList(head, tail) }

fun Arb.Companion.orderStatus(): Arb<OrderStatus> =
    Arb.choice(
        Arb.constant(OrderStatus.Draft),
        Arb.constant(OrderStatus.PendingPayment),
        Arb.testInstant().map { OrderStatus.Paid(it) },
        Arb.testInstant().map { OrderStatus.Fulfilling(it) },
        Arb.string(1..12, ALPHANUMERIC.joinToString("")).map { OrderStatus.Shipped(it) },
        Arb.testInstant().map { OrderStatus.Delivered(it) },
        Arb.string(1..20, ALPHANUMERIC.joinToString("")).map { OrderStatus.Cancelled(it) },
        Arb.bind(Arb.testInstant(), Arb.money()) { at, amount -> OrderStatus.Refunded(at, amount) },
    )

fun Arb.Companion.order(): Arb<Order> =
    Arb.bind(
        Arb.orderId(),
        Arb.customerId(),
        Arb.orderLines(),
        Arb.shippingAddress(),
        Arb.orderStatus(),
    ) { id, customerId, lines, address, status -> Order(id, customerId, lines, address, status) }
