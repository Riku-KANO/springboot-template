package com.example.template.application.port

import arrow.core.Either
import com.example.template.domain.error.OrderError
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.OrderId
import java.time.Instant

/**
 * 決済ゲートウェイへの請求確定 (与信+実売上を一体で行う "charge") を表すアウトバウンドポート。
 * 実装は :adapter-messaging or 専用の決済アダプタが後続チャンクで提供する。
 *
 * 与信 (authorize) と実売上 (capture) を2フェーズに分けず単一の [charge] にまとめているのは、
 * このテンプレートが「決済ゲートウェイ連携のポート設計パターン」を示すことが目的であり、
 * 与信/実売上の分離は決済プロバイダごとに事情が異なる (実装の詳細) ためである。
 * 2フェーズが必要なプロバイダを使う場合は、このポートの実装内部で両方の API 呼び出しを
 * 行えばよく、:application 側のユースケースの形は変わらない。
 *
 * paidAt はこのポートが返す [PaymentCharge] の一部として決済プロバイダ側の確定時刻を採用する。
 * PayOrderService が自前の Clock で「今」を刻まないのは、決済完了時刻は決済プロバイダという
 * 外部システムの権威に委ねるべき情報であり、アプリケーションサーバーの時計とズレて良いものではないため。
 * 実装は同じ [PaymentIdempotencyKey] の再送に対して、同じ請求結果を返さなければならない。
 */
fun interface PaymentGatewayPort {
    suspend fun charge(
        orderId: OrderId,
        amount: Money,
        idempotencyKey: PaymentIdempotencyKey,
    ): Either<OrderError, PaymentCharge>
}

/** 決済ゲートウェイでの請求確定結果。 */
data class PaymentCharge(
    val paidAt: Instant,
    val providerTransactionId: String,
)
