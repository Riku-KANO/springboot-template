package com.example.template.domain.order

import arrow.core.nonEmptyListOf
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.shared.postalCode
import com.example.template.domain.testfixtures.customerId
import com.example.template.domain.testfixtures.orderId
import com.example.template.domain.testfixtures.orderLine
import com.example.template.domain.testfixtures.shippingAddress
import com.example.template.domain.testfixtures.shouldBeRight
import io.kotest.property.Arb
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val PRINTABLE = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789 -"

/**
 * KSP が生成した Lens/Traversal を実際に検証する。
 * Lens 則 (get-set / set-get / set-set) をプロパティベースで検証することで、
 * 「ShippingAddress.postalCode という Lens が本当に Lens 則を満たしている」ことを確認する
 * (自動生成コードだからといって無条件に信頼はしない、というテスト態度)。
 */
class OrderOpticsTest {
    @Test
    fun `postalCode Lens は get-set 則を満たす (取り出した値をそのまま書き戻すと元に戻る)`() =
        runTest {
            checkAll(Arb.shippingAddress()) { address ->
                val roundTripped = ShippingAddress.postalCode.set(address, ShippingAddress.postalCode.get(address))
                assertEquals(address, roundTripped)
            }
        }

    @Test
    fun `postalCode Lens は set-get 則を満たす (書き込んだ値がそのまま読み出せる)`() =
        runTest {
            checkAll(Arb.shippingAddress(), Arb.string(1..10, PRINTABLE)) { address, newPostalCode ->
                val updated = ShippingAddress.postalCode.set(address, newPostalCode)
                assertEquals(newPostalCode, ShippingAddress.postalCode.get(updated))
            }
        }

    @Test
    fun `postalCode Lens は set-set 則を満たす (2回目の set が1回目を上書きする)`() =
        runTest {
            checkAll(
                Arb.shippingAddress(),
                Arb.string(1..10, PRINTABLE),
                Arb.string(1..10, PRINTABLE),
            ) { address, first, second ->
                val setTwice = ShippingAddress.postalCode.set(ShippingAddress.postalCode.set(address, first), second)
                val setOnce = ShippingAddress.postalCode.set(address, second)
                assertEquals(setOnce, setTwice)
            }
        }

    @Test
    fun `normalizePostalCode は全角数字混じりの郵便番号を半角 NNN-NNNN に正規化する`() {
        val address =
            ShippingAddress(
                recipientName = "Taro",
                postalCode = "１０００００１", // 全角の "1000001"
                prefecture = "Tokyo",
                city = "Chiyoda",
                addressLine1 = "1-1-1",
            )
        val order = fixedOrder(address)

        val normalized = normalizePostalCode(order)

        assertEquals("100-0001", normalized.address.postalCode)
    }

    @Test
    fun `addSurchargeToAllLines は全明細の単価に一律加算する`() =
        runTest {
            checkAll(
                Arb.orderId(),
                Arb.customerId(),
                Arb.orderLine(),
                Arb.orderLine(),
                Arb.shippingAddress(),
            ) { id, customerId, lineA, lineB, address ->
                val order = Order(id, customerId, nonEmptyListOf(lineA, lineB), address, OrderStatus.Draft)

                val surcharged = addSurchargeToAllLines(order, 10)

                surcharged.lines.all.zip(order.lines.all).forEach { (after, before) ->
                    assertEquals(before.unitPrice.amount.value + 10, after.unitPrice.amount.value)
                    // 数量や sku は Traversal の対象外なので変化しない
                    assertEquals(before.sku, after.sku)
                    assertEquals(before.quantity, after.quantity)
                }
            }
        }

    private fun fixedOrder(address: ShippingAddress): Order {
        val line = OrderLine.createFailFast("SKU-1", 1, 100, java.util.Currency.getInstance("JPY")).shouldBeRight()
        return Order(
            id = OrderId.create("order-1").shouldBeRight(),
            customerId = CustomerId.create("customer-1").shouldBeRight(),
            lines = nonEmptyListOf(line),
            address = address,
            status = OrderStatus.Draft,
        )
    }
}
