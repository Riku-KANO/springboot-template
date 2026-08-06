package com.example.template.application.order

import arrow.core.EitherNel
import arrow.core.NonEmptyList
import arrow.core.mapOrAccumulate
import arrow.core.raise.either
import arrow.core.raise.zipOrAccumulate
import com.example.template.domain.error.ValidationError
import com.example.template.domain.order.OrderLine
import com.example.template.domain.shared.CustomerId
import com.example.template.domain.shared.OrderId
import com.example.template.domain.shared.ShippingAddress
import java.util.Currency

/** OrderLine.create に渡す前の、Web リクエスト等から来る生の (未検証の) 明細1行。 */
data class RawOrderLine(
    val sku: String,
    val quantity: Int,
    val unitPriceMinor: Long,
)

/**
 * CreateOrder ユースケースへの入力。すべてのフィールドが検証済みの domain 型
 * (OrderId, CustomerId, NonEmptyList<OrderLine>) であることを、companion object の
 * [create] を通してのみインスタンス化できることで保証する (コンストラクタは公開されているが、
 * 生の文字列/数値からここに来るには必ず [create] を通る運用とする)。
 *
 * ShippingAddress は生フィールドのまま保持している。:domain の ShippingAddress は
 * (postalCode 等の) スマートコンストラクタ検証を持たない optics 専用の値であり、
 * :application 側で独自に「郵便番号の形式チェック」のような業務ルールを新設するのは、
 * ユースケース層に業務ルールを持ち込まないという方針 (このモジュールの説明を参照) に反する。
 * 検証を追加したくなった場合は :domain の ShippingAddress にスマートコンストラクタを
 * 追加するのが筋であり、このコマンドはあくまで :domain が提供する検証だけを組み合わせる。
 */
data class CreateOrderCommand(
    val orderId: OrderId,
    val customerId: CustomerId,
    val lines: NonEmptyList<OrderLine>,
    val address: ShippingAddress,
) {
    companion object {
        /**
         * 生の入力から [CreateOrderCommand] を累積バリデーションで組み立てる。
         *
         * orderId・customerId・lines (明細群) という3つの独立した入力を `zipOrAccumulate` で
         * 並行に検証し、そのうち何個が壊れていても "全部の間違い" を1回の呼び出しで返す。
         * lines 側は [validateLines] (下記) に委譲し、その結果 (最大で明細数 x 3件のエラーを
         * 持ちうる EitherNel) を `RaiseAccumulate.bindNel` で外側の累積へフラット化して合流させる。
         *
         * これと対になるのが :application 側の CreateOrderService が呼ぶ `Order.create` で、
         * こちらは集約レベルの重複 SKU チェックをフェイルファストの Either で行う
         * (domain/order/Order.kt 参照)。「入力フィールドの検証は累積・集約レベルのビジネスルールは
         * フェイルファスト」という使い分けを、コマンド生成とユースケース本体とで実演している。
         */
        fun create(
            rawOrderId: String,
            rawCustomerId: String,
            rawLines: NonEmptyList<RawOrderLine>,
            currency: Currency,
            address: ShippingAddress,
        ): EitherNel<ValidationError, CreateOrderCommand> =
            either {
                zipOrAccumulate(
                    { OrderId.create(rawOrderId).bind() },
                    { CustomerId.create(rawCustomerId).bind() },
                    { validateLines(rawLines, currency).bindNel() },
                ) { orderId, customerId, lines -> CreateOrderCommand(orderId, customerId, lines, address) }
            }

        /**
         * 明細群をまとめて検証する。
         *
         * 明細を「先頭と残り」に分けて順番に `.bindNel()` するのではなく、[rawLines] 全体を
         * 1回の `mapOrAccumulate` にかけているのが重要な点: `.bindNel()` は失敗した瞬間に
         * その場の処理を打ち切る (通常の Raise の短絡評価) ため、もし先頭の明細だけを
         * 先に検証してしまうと、先頭が不正だった場合に残りの明細のエラーを一切収集できずに
         * 打ち切られてしまう (実際にこの実装で一度そのバグを踏んだ)。`mapOrAccumulate` は
         * 要素ごとに独立して検証し、成功した要素と失敗した要素を問わず全要素を処理してから
         * まとめてエラーを返すため、「明細Aのskuが不正、明細Bのquantityが不正」のような
         * 複数明細にまたがる不正も1回の呼び出しですべて拾える。
         *
         * ここで使う `mapOrAccumulate` は (ambient な Raise スコープを前提とする版とは別の)
         * List を直接受け取り自己完結した Either を返すトップレベル関数
         * ( `arrow.core.mapOrAccumulate` ) で、成功時に NonEmptyList を返してくれるため、
         * 明細が1件も無いという (型的にありえないはずの) ケースを手動で詰め直す必要がない。
         */
        private fun validateLines(
            rawLines: NonEmptyList<RawOrderLine>,
            currency: Currency,
        ): EitherNel<ValidationError, NonEmptyList<OrderLine>> =
            rawLines.mapOrAccumulate { raw ->
                OrderLine.create(raw.sku, raw.quantity, raw.unitPriceMinor, currency).bindNel()
            }
    }
}
