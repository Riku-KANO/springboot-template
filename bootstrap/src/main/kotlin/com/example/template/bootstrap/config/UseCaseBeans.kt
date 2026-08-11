package com.example.template.bootstrap.config

import com.example.template.application.order.CancelOrder
import com.example.template.application.order.CancelOrderService
import com.example.template.application.order.CreateOrder
import com.example.template.application.order.CreateOrderService
import com.example.template.application.order.DeliverOrder
import com.example.template.application.order.DeliverOrderService
import com.example.template.application.order.FindOrder
import com.example.template.application.order.FindOrderService
import com.example.template.application.order.ListOrders
import com.example.template.application.order.ListOrdersService
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
import org.springframework.beans.factory.BeanRegistrarDsl

/**
 * :application のユースケース実装を Bean 登録する。
 *
 * :application は Spring への依存を一切持たないことが要件のモジュールであり (application/build.gradle.kts
 * 参照)、CreateOrderService 等の実装クラス自体には `@Component` を付けられない。かといって
 * :bootstrap 側で「`@Configuration` + `@Bean` メソッドを何個も並べるだけ」のクラスを書くのは、
 * 単純なコンストラクタ注入の羅列のために CGLIB プロキシ生成のコストやクラスファイルの
 * 冗長さを背負うだけで得るものがない。Spring 7 の [BeanRegistrarDsl] は、この「ただ依存を
 * 解決してインスタンス化するだけ」のクラス群を軽量に登録できる。
 *
 * `bean<T>()` は呼び出し先のコンストラクタパラメータの型から reified 型引数 `T` を推論し、
 * その型の Bean を1つだけ解決する (複数候補がある場合は名前解決や `@Primary` が必要になるが、
 * ここで登場するポートは各インターフェースにつき常に1つしか Bean を登録しないため問題にならない)。
 */
class UseCaseBeanRegistrar :
    BeanRegistrarDsl({
        registerBean<CreateOrder> { CreateOrderService(bean()) }
        registerBean<FindOrder> { FindOrderService(bean()) }
        registerBean<ListOrders> { ListOrdersService(bean()) }
        registerBean<SubmitOrderForPayment> { SubmitOrderForPaymentService(bean(), bean()) }
        registerBean<PayOrder> { PayOrderService(bean(), bean(), bean(), bean()) }
        registerBean<StartFulfillment> { StartFulfillmentService(bean(), bean()) }
        registerBean<ShipOrder> { ShipOrderService(bean(), bean()) }
        registerBean<DeliverOrder> { DeliverOrderService(bean(), bean()) }
        registerBean<CancelOrder> { CancelOrderService(bean(), bean()) }
        registerBean<RefundOrder> { RefundOrderService(bean(), bean()) }
        registerBean<ReconcileSettlementRecord> { ReconcileSettlementService(bean(), bean(), bean()) }
    })
