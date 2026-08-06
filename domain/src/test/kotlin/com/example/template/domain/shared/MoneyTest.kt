package com.example.template.domain.shared

import com.example.template.domain.error.ValueError
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.util.Currency

private val JPY: Currency = Currency.getInstance("JPY")
private val USD: Currency = Currency.getInstance("USD")

class MoneyTest {
    @Test
    fun `同じ通貨同士の plus は金額を合算する`() {
        val a = Money.of(BigDecimal("100"), JPY).shouldBeRight()
        val b = Money.of(BigDecimal("50"), JPY).shouldBeRight()
        val sum = a.plus(b).shouldBeRight()
        assertEquals(150L, sum.amount.value)
    }

    @Test
    fun `通貨が異なる plus は CurrencyMismatch を返す`() {
        val jpy = Money.of(BigDecimal("100"), JPY).shouldBeRight()
        val usd = Money.of(BigDecimal("100"), USD).shouldBeRight()
        val error = jpy.plus(usd).shouldBeLeftOfType<ValueError.CurrencyMismatch>()
        assertEquals(JPY, error.expected)
        assertEquals(USD, error.actual)
    }

    @Test
    fun `multiply は最小通貨単位を整数倍する`() {
        val money = Money.of(BigDecimal("10"), JPY).shouldBeRight()
        val tripled = money.multiply(3).shouldBeRight()
        assertEquals(30L, tripled.amount.value)
    }

    @Test
    fun `of は通貨の小数桁数に応じて BigDecimal を最小通貨単位に変換する`() {
        // USD は defaultFractionDigits = 2 なので 12.34 ドル = 1234 セント
        val usd = Money.of(BigDecimal("12.34"), USD).shouldBeRight()
        assertEquals(1234L, usd.amount.value)

        // JPY は defaultFractionDigits = 0 なので 1234 円はそのまま 1234
        val jpy = Money.of(BigDecimal("1234"), JPY).shouldBeRight()
        assertEquals(1234L, jpy.amount.value)
    }

    @Test
    fun `of は最小通貨単位未満の端数を持つ BigDecimal を拒否する`() {
        // 1セント未満の端数はどの整数の最小通貨単位でも表現できない
        Money.of(BigDecimal("12.345"), USD).shouldBeLeftOfType<ValueError.FractionalMinorUnit>()
    }

    @Test
    fun `zero はその通貨の0円を表す`() {
        val zero = Money.zero(JPY)
        assertEquals(0L, zero.amount.value)
        assertEquals(JPY, zero.currency)
    }
}
