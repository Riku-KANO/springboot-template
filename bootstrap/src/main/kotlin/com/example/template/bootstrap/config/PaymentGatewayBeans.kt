package com.example.template.bootstrap.config

import com.example.template.adapter.messaging.resilience.ResilientPaymentGatewayAdapter
import com.example.template.application.port.PaymentGatewayPort
import com.example.template.bootstrap.paymentgateway.StubPaymentGatewayAdapter
import org.springframework.beans.factory.BeanRegistrarDsl

/**
 * [PaymentGatewayPort] の Bean 登録。
 *
 * このテンプレートには実在の決済プロバイダ連携は含まれない ([PaymentGatewayPort] の KDoc が
 * 明言する通り、これはポート設計パターンを示すためのテンプレートである)。そのため composition root
 * である :bootstrap は [StubPaymentGatewayAdapter] という「常に成功したことにする」最小限のスタブを
 * 実運用のプレースホルダとして提供する (詳細はそのクラスの KDoc を参照)。
 *
 * [ResilientPaymentGatewayAdapter] (:adapter-messaging, arrow-resilience によるリトライ/
 * サーキットブレーカーのデコレータ。それ自体も `@Component` を持たないプレーンなクラス) で
 * スタブを包んでから `PaymentGatewayPort` として公開する。スタブ自身を独立した名前付き Bean として
 * 登録せず、無名のまま delegate 引数へ直接渡しているのは、「`PaymentGatewayPort` 型の Bean は
 * 常に1つだけ」という状態を保証するため — 2つ登録してしまうと、他の箇所 (config/UseCaseBeans.kt の
 * `PayOrderService` 等) が `bean<PaymentGatewayPort>()` を呼んだときに複数候補で解決に失敗する。
 */
class PaymentGatewayBeanRegistrar :
    BeanRegistrarDsl({
        registerBean<PaymentGatewayPort> { ResilientPaymentGatewayAdapter(delegate = StubPaymentGatewayAdapter()) }
    })
