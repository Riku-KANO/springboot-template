package com.example.template.application.order

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import com.example.template.domain.error.ValueError
import com.example.template.domain.testfixtures.customerId
import com.example.template.domain.testfixtures.orderId
import com.example.template.domain.testfixtures.shippingAddress
import com.example.template.domain.testfixtures.shouldBeLeft
import com.example.template.domain.testfixtures.shouldBeRight
import com.example.template.domain.testfixtures.testCurrency
import io.kotest.property.Arb
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")

private fun validLine(sku: String = "SKU-1") = RawOrderLine(sku, 1, 500)

/**
 * CreateOrderCommand.create の累積バリデーションを検証する。
 * 「3項目が独立に壊れていれば3件のエラーが返る」ことがこのテストの中心的な主張であり、
 * domain の OrderLineAccumulationTest (OrderLine.create 単体の累積) を1段上の
 * ユースケース入力レベルで再演するテストにあたる。
 */
class CreateOrderCommandTest {
    @Test
    fun `orderId・customerId・明細1件のsku が独立に不正なら3件のエラーを返す`() {
        val rawLines = nonEmptyListOf(RawOrderLine("invalid sku with spaces!", 1, 500))

        val errors =
            CreateOrderCommand
                .create(
                    rawOrderId = "",
                    rawCustomerId = "",
                    rawLines = rawLines,
                    currency = JPY,
                    address = fixedAddress(),
                ).shouldBeLeft()

        assertEquals(3, errors.size)
        assert(errors.any { it is ValueError.BlankOrderId }) { "BlankOrderId が含まれていない: $errors" }
        assert(errors.any { it is ValueError.BlankCustomerId }) { "BlankCustomerId が含まれていない: $errors" }
        assert(errors.any { it is ValueError.InvalidSkuFormat }) { "InvalidSkuFormat が含まれていない: $errors" }
    }

    @Test
    fun `1明細のsku・quantity・unitPrice が全て不正なら OrderLine 由来の3件のエラーだけが flatten されて返る`() {
        // orderId・customerId は valid、明細1件だけが3項目とも不正
        val rawLines = nonEmptyListOf(RawOrderLine("invalid sku with spaces!", 0, -100))

        val errors =
            CreateOrderCommand
                .create(
                    rawOrderId = "order-1",
                    rawCustomerId = "customer-1",
                    rawLines = rawLines,
                    currency = JPY,
                    address = fixedAddress(),
                ).shouldBeLeft()

        // bindNel が「明細1件の中の3エラー」をきちんと外側の NonEmptyList にフラット化していることの検証。
        // (もし flatten されず1つの複合エラーに潰れてしまうなら、ここは size=1 になってしまう)
        assertEquals(3, errors.size)
        assert(errors.any { it is ValueError.InvalidSkuFormat })
        assert(errors.any { it is ValueError.NonPositiveQuantity })
        assert(errors.any { it is ValueError.NegativeMoneyAmount })
    }

    @Test
    fun `2明細それぞれに1件ずつ不正なフィールドがあれば tail の mapOrAccumulate も含めて2件のエラーを返す`() {
        val rawLines = NonEmptyList(RawOrderLine("BAD SKU!", 1, 500), listOf(RawOrderLine("SKU-2", -1, 500)))

        val errors =
            CreateOrderCommand
                .create(
                    rawOrderId = "order-1",
                    rawCustomerId = "customer-1",
                    rawLines = rawLines,
                    currency = JPY,
                    address = fixedAddress(),
                ).shouldBeLeft()

        assertEquals(2, errors.size)
        assert(errors.any { it is ValueError.InvalidSkuFormat })
        assert(errors.any { it is ValueError.NonPositiveQuantity })
    }

    @Test
    fun `全フィールドが有効なら CreateOrderCommand を作る`() {
        val rawLines = nonEmptyListOf(validLine())

        val command =
            CreateOrderCommand
                .create(
                    rawOrderId = "order-1",
                    rawCustomerId = "customer-1",
                    rawLines = rawLines,
                    currency = JPY,
                    address = fixedAddress(),
                ).shouldBeRight()

        assertEquals("order-1", command.orderId.value)
        assertEquals("customer-1", command.customerId.value)
        assertEquals(1, command.lines.size)
    }

    @Test
    fun `domain の Arb が生成する有効な値からは常に Right が返る (property based)`() =
        runTest {
            checkAll(Arb.orderId(), Arb.customerId(), Arb.shippingAddress(), Arb.testCurrency()) { orderId, customerId, address, currency ->
                val rawLines = nonEmptyListOf(RawOrderLine("SKU-OK", 1, 500))
                CreateOrderCommand
                    .create(orderId.value, customerId.value, rawLines, currency, address)
                    .shouldBeRight()
            }
        }

    private fun fixedAddress() =
        com.example.template.domain.shared.ShippingAddress(
            recipientName = "Taro",
            postalCode = "100-0001",
            prefecture = "Tokyo",
            city = "Chiyoda",
            addressLine1 = "1-1-1",
        )
}
