package com.example.template.domain.order

import com.example.template.domain.error.ValueError
import com.example.template.domain.testfixtures.shouldBeLeft
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")

/**
 * OrderLine.create (累積) と OrderLine.createFailFast (フェイルファスト) を
 * 「同じ、3項目すべてが不正な入力」に対して実行し、返ってくるエラー件数の違いを検証する。
 * これが本モジュールで「累積バリデーション」を実演する中心的なテストケース。
 */
class OrderLineAccumulationTest {
    // sku・quantity・unitPriceMinor の3つとも不正な入力
    private val invalidSku = "invalid sku with spaces!"
    private val invalidQuantity = 0
    private val invalidUnitPriceMinor = -100L

    @Test
    fun `create は3項目とも不正なら3件のエラーを NonEmptyList に集約する`() {
        val errors = OrderLine.create(invalidSku, invalidQuantity, invalidUnitPriceMinor, JPY).shouldBeLeft()

        assertEquals(3, errors.size)
        assert(errors.any { it is ValueError.InvalidSkuFormat }) { "InvalidSkuFormat が含まれていない: $errors" }
        assert(errors.any { it is ValueError.NonPositiveQuantity }) { "NonPositiveQuantity が含まれていない: $errors" }
        assert(errors.any { it is ValueError.NegativeMoneyAmount }) { "NegativeMoneyAmount が含まれていない: $errors" }
    }

    @Test
    fun `createFailFast は同じ入力に対して最初の1件のエラーだけを返す (対比)`() {
        // フィールドの検証順序 (sku -> quantity -> unitPrice) の最初、sku のエラーのみが返る
        OrderLine
            .createFailFast(invalidSku, invalidQuantity, invalidUnitPriceMinor, JPY)
            .shouldBeLeftOfType<ValueError.InvalidSkuFormat>()
    }

    @Test
    fun `create は全項目が有効なら OrderLine を作る`() {
        val line = OrderLine.create("SKU-1", 2, 500, JPY).shouldBeRight()
        assertEquals("SKU-1", line.sku.value)
        assertEquals(2, line.quantity.value)
        assertEquals(500L, line.unitPrice.amount.value)
    }

    @Test
    fun `create は1項目だけ不正なら1件のエラーを返す`() {
        val errors = OrderLine.create(invalidSku, 1, 100, JPY).shouldBeLeft()
        assertEquals(1, errors.size)
        assert(errors.head is ValueError.InvalidSkuFormat)
    }
}
