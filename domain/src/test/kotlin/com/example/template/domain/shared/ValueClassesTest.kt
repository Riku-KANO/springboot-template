package com.example.template.domain.shared

import com.example.template.domain.error.ValueError
import com.example.template.domain.testfixtures.shouldBeLeft
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.arbitrary.string
import io.kotest.property.checkAll
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

private const val ALPHANUMERIC = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

/**
 * 値クラスのスマートコンストラクタをプロパティベースで検証する。
 *
 * 「有効な入力の集合からどう生成しても常に成功する」ことと「境界値を1つ外れると
 * 必ず拒否される」ことの両方を見て初めて、スマートコンストラクタが正しく機能している
 * と言える。前者だけだと「実は何も検証していない」ケースを見逃す。
 */
class ValueClassesTest {
    @Test
    fun `OrderId create は 1〜64文字の英数字を常に受理する`() =
        runTest {
            checkAll(Arb.string(1..64, ALPHANUMERIC)) { raw ->
                val id = OrderId.create(raw).shouldBeRight()
                assertEquals(raw, id.value)
            }
        }

    @Test
    fun `OrderId create は空文字列を拒否する`() {
        val error = OrderId.create("   ").shouldBeLeftOfType<ValueError.BlankOrderId>()
        assertEquals(ValueError.BlankOrderId, error)
    }

    @Test
    fun `OrderId create は65文字以上を拒否する`() {
        val tooLong = "a".repeat(65)
        val error = OrderId.create(tooLong).shouldBeLeftOfType<ValueError.OrderIdTooLong>()
        assertEquals(65, error.length)
    }

    @Test
    fun `CustomerId create は 1〜64文字の英数字を常に受理する`() =
        runTest {
            checkAll(Arb.string(1..64, ALPHANUMERIC)) { raw ->
                CustomerId.create(raw).shouldBeRight()
            }
        }

    @Test
    fun `CustomerId create は空文字列を拒否する`() {
        CustomerId.create("").shouldBeLeftOfType<ValueError.BlankCustomerId>()
    }

    @Test
    fun `Sku create は 1〜32文字の英数字とハイフンを常に受理する`() =
        runTest {
            checkAll(Arb.string(1..32, "$ALPHANUMERIC-")) { raw ->
                Sku.create(raw).shouldBeRight()
            }
        }

    @Test
    fun `Sku create は不正な文字を含む入力を拒否する`() {
        Sku.create("invalid sku!").shouldBeLeftOfType<ValueError.InvalidSkuFormat>()
    }

    @Test
    fun `Sku create は小文字を大文字に正規化する`() {
        val sku = Sku.create("abc-123").shouldBeRight()
        assertEquals("ABC-123", sku.value)
    }

    @Test
    fun `Quantity create は 1以上 MAX_QUANTITY 以下を常に受理する`() =
        runTest {
            checkAll(Arb.long(1L..ValueError.MAX_QUANTITY.toLong())) { raw ->
                Quantity.create(raw.toInt()).shouldBeRight()
            }
        }

    @Test
    fun `Quantity create は0以下を拒否する`() {
        val error = Quantity.create(0).shouldBeLeftOfType<ValueError.NonPositiveQuantity>()
        assertEquals(0, error.raw)
        Quantity.create(-1).shouldBeLeftOfType<ValueError.NonPositiveQuantity>()
    }

    @Test
    fun `Quantity create は MAX_QUANTITY を超える値を拒否する`() {
        val error = Quantity.create(ValueError.MAX_QUANTITY + 1).shouldBeLeftOfType<ValueError.QuantityTooLarge>()
        assertEquals(ValueError.MAX_QUANTITY + 1, error.raw)
    }

    @Test
    fun `MoneyMinor create は0以上の値を常に受理する`() =
        runTest {
            checkAll(Arb.long(0L..Long.MAX_VALUE / 2)) { raw ->
                MoneyMinor.create(raw).shouldBeRight()
            }
        }

    @Test
    fun `MoneyMinor create は負の値を拒否する`() {
        val error = MoneyMinor.create(-1).shouldBeLeftOfType<ValueError.NegativeMoneyAmount>()
        assertEquals(-1L, error.raw)
    }

    @Test
    fun `MoneyMinor plus はオーバーフローを ValidationError として検出する`() {
        val huge = MoneyMinor.create(Long.MAX_VALUE).shouldBeRight()
        val one = MoneyMinor.create(1).shouldBeRight()
        val error = huge.plus(one).shouldBeLeft()
        assertEquals(ValueError.MoneyAmountOverflow, error)
    }
}
