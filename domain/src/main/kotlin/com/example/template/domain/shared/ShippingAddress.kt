package com.example.template.domain.shared

import arrow.optics.optics

/**
 * 配送先住所。
 *
 * @optics を付けると KSP (arrow-optics-ksp-plugin) が companion object に
 * Lens プロパティ (例: `ShippingAddress.postalCode: Lens<ShippingAddress, String>`) を
 * 生成する。companion object 自体は空でよい (生成コードがそこへ拡張プロパティとして
 * 追加される)。実際の利用箇所は order/OrderOptics.kt を参照。
 */
@optics
data class ShippingAddress(
    val recipientName: String,
    val postalCode: String,
    val prefecture: String,
    val city: String,
    val addressLine1: String,
    val addressLine2: String? = null,
) {
    companion object
}
