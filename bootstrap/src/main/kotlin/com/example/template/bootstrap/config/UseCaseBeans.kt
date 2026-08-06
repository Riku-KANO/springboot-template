// beans { } (org.springframework.context.support.beans) は Spring Framework 7 で
// BeanRegistrarDsl 導入に伴い @Deprecated 化されているが、Chunk 6 の依頼で名指しされた
// 「関数型の beans { } DSL」そのものであり、意図的に採用を継続する (ビルド出力を deprecation
// 警告で汚さないためだけの抑制であり、動作を変えるものではない)。
@file:Suppress("DEPRECATION")

package com.example.template.bootstrap.config

import com.example.template.application.order.CancelOrder
import com.example.template.application.order.CancelOrderService
import com.example.template.application.order.CreateOrder
import com.example.template.application.order.CreateOrderService
import com.example.template.application.order.DeliverOrder
import com.example.template.application.order.DeliverOrderService
import com.example.template.application.order.FindOrder
import com.example.template.application.order.FindOrderService
import com.example.template.application.order.PayOrder
import com.example.template.application.order.PayOrderService
import com.example.template.application.order.RefundOrder
import com.example.template.application.order.RefundOrderService
import com.example.template.application.order.ShipOrder
import com.example.template.application.order.ShipOrderService
import com.example.template.application.order.StartFulfillment
import com.example.template.application.order.StartFulfillmentService
import com.example.template.application.order.SubmitOrderForPayment
import com.example.template.application.order.SubmitOrderForPaymentService
import com.example.template.application.settlement.ReconcileSettlementRecord
import com.example.template.application.settlement.ReconcileSettlementService
import org.springframework.context.support.beans

/**
 * :application のユースケース実装を Bean 登録する。
 *
 * :application は Spring への依存を一切持たないことが要件のモジュールであり (application/build.gradle.kts
 * 参照)、CreateOrderService 等の実装クラス自体には `@Component` を付けられない。かといって
 * :bootstrap 側で「`@Configuration` + `@Bean` メソッドを何個も並べるだけ」のクラスを書くのは、
 * 単純なコンストラクタ注入の羅列のために CGLIB プロキシ生成のコストやクラスファイルの
 * 冗長さを背負うだけで得るものがない。Spring 5.2 以降の関数型 Bean 定義 DSL
 * (`org.springframework.context.support.beans`) はまさにこの「ただ依存を解決してインスタンス化
 * するだけ」のクラス群のための機構であり、`ApplicationContextInitializer` として軽量に登録できる。
 *
 * `ref<T>()` は呼び出し先のコンストラクタパラメータの型から reified 型引数 `T` を推論し、
 * その型の Bean を1つだけ解決する (複数候補がある場合は名前解決や `@Primary` が必要になるが、
 * ここで登場するポートは各インターフェースにつき常に1つしか Bean を登録しないため問題にならない)。
 */
fun useCaseBeans() =
    beans {
        bean<CreateOrder> { CreateOrderService(ref()) }
        bean<FindOrder> { FindOrderService(ref()) }
        bean<SubmitOrderForPayment> { SubmitOrderForPaymentService(ref(), ref()) }
        bean<PayOrder> { PayOrderService(ref(), ref(), ref()) }
        bean<StartFulfillment> { StartFulfillmentService(ref(), ref()) }
        bean<ShipOrder> { ShipOrderService(ref(), ref()) }
        bean<DeliverOrder> { DeliverOrderService(ref(), ref()) }
        bean<CancelOrder> { CancelOrderService(ref(), ref()) }
        bean<RefundOrder> { RefundOrderService(ref(), ref()) }
        bean<ReconcileSettlementRecord> { ReconcileSettlementService(ref(), ref(), ref()) }
    }
