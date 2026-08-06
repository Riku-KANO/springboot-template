package com.example.template.domain.order

import arrow.core.getOrElse
import arrow.optics.Lens
import arrow.optics.Traversal
import arrow.optics.dsl.every
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.ShippingAddress
import com.example.template.domain.shared.postalCode

/*
 * KSP が Order / OrderLine / ShippingAddress の `@optics` から生成した Lens を、
 * 実際のドメインロジックの中で使う例。テストの中でしか使わない optics は
 * 「生成はされているが誰も使っていない」状態になりがちなので、ここで実利用する。
 */

// --- Lens の例: Order -> ShippingAddress -> postalCode という2段の Lens を合成する ---

/**
 * `Order.address` (Order 内の Lens) と `ShippingAddress.postalCode`
 * (ShippingAddress 内の Lens) を compose した、Order から直接郵便番号にアクセスできる Lens。
 * 「Order の中の ShippingAddress の中の postalCode」を毎回 `order.address.postalCode` と
 * 手でたどらずに、合成 Lens 1個として扱える。
 */
private val orderPostalCode: Lens<Order, String> = Order.address compose ShippingAddress.postalCode

/**
 * 全角数字やスペース混じりの郵便番号を "NNN-NNNN" の半角形式に正規化する。
 * Lens.modify は get → 変換 → set を1回で行う。
 */
fun normalizePostalCode(order: Order): Order = orderPostalCode.modify(order, ::toNormalizedPostalCode)

private fun toNormalizedPostalCode(raw: String): String {
    val digits = raw.map(::toHalfWidthDigit).filter(Char::isDigit)
    return if (digits.size == 7) "${digits.take(3).joinToString("")}-${digits.takeLast(4).joinToString("")}" else raw.trim()
}

/** 全角数字 (U+FF10-FF19) を半角数字に変換する。それ以外の文字はそのまま返す。 */
private fun toHalfWidthDigit(c: Char): Char = if (c.code in FULLWIDTH_DIGIT_RANGE) (c.code - FULLWIDTH_TO_HALFWIDTH_OFFSET).toChar() else c

private val FULLWIDTH_DIGIT_RANGE = 0xFF10..0xFF19
private const val FULLWIDTH_TO_HALFWIDTH_OFFSET = 0xFF10 - '0'.code

// --- Traversal の例: Order.lines の全要素の単価に一律の値上げを適用する ---

/**
 * `Order.lines` (NonEmptyList<OrderLine> への Lens) を `Every.nonEmptyList()` と合成して
 * 「Order 配下の全 OrderLine」を覗く Traversal にし、さらに `OrderLine.unitPrice` と合成して
 * 「Order 配下の全 OrderLine の単価」を1つの Traversal として扱えるようにしたもの。
 */
private val allUnitPrices: Traversal<Order, Money> = Order.lines.every.compose(OrderLine.unitPrice)

/**
 * 全明細の単価に一律のサーチャージ (最小通貨単位) を加算する。
 *
 * Traversal.modify は `(A) -> B` という全域関数を要求し Either を返せないため、
 * ここでの加算が「数学的に必ず非負になる」ことを呼び出し側で保証した上で
 * スマートコンストラクタを通す (万一の理論上あり得ない失敗は元の値にフォールバックする)。
 * 現実的な金額レンジで Long オーバーフローが起きることは想定していない。
 */
fun addSurchargeToAllLines(
    order: Order,
    surchargeMinor: Long,
): Order =
    allUnitPrices.modify(order) { money ->
        val newMinor = (money.amount.value + surchargeMinor).coerceAtLeast(0)
        money.copy(amount = MoneyMinor.create(newMinor).getOrElse { money.amount })
    }
