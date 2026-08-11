package com.example.template.bootstrap.config

import com.example.template.adapter.persistence.order.OrderRepositoryAdapter
import com.example.template.adapter.persistence.payment.PaymentAttemptRepositoryAdapter
import com.example.template.adapter.persistence.settlement.SettlementRepositoryAdapter
import com.example.template.adapter.persistence.tx.R2dbcTxRunner
import com.example.template.application.port.OrderRepository
import com.example.template.application.port.PaymentAttemptRepository
import com.example.template.application.port.SettlementRepository
import com.example.template.application.port.TxRunner
import org.springframework.beans.factory.BeanRegistrarDsl

/**
 * :adapter-persistence が提供するポート実装を Bean 登録する。
 *
 * [OrderRepositoryAdapter] / [SettlementRepositoryAdapter] / [R2dbcTxRunner] は意図的に
 * `@Component` が付いていない、Spring 非依存のプレーンな Kotlin クラスである (Chunk 3 からの
 * 申し送り。「どの実装をどのポートに割り当てるか」という composition root の意思決定を、
 * これらのクラス自身にではなく :bootstrap 側に一元管理させるための設計)。とはいえ
 * `DatabaseClient` / `TransactionalOperator` という Spring の Bean をコンストラクタで受け取るため、
 * :application のユースケース群のような「完全な Spring フリー」ではない。ここではその方針どおり、
 * Spring 7 の [BeanRegistrarDsl] でコンストラクタ注入する。
 *
 * ##### 単一の TransactionalOperator を共有する (要件4)
 * `OrderRepositoryAdapter` (save 内で自前にトランザクションを開始する) と `R2dbcTxRunner`
 * (:application 層の TxRunner の実装) は、ここでそれぞれ `bean<TransactionalOperator>()` を
 * 呼んでいるが、[R2dbcTransactionConfig] が定義する `TransactionalOperator` Bean はシングルトンなので
 * 両者は同一のインスタンスを受け取る。R2DBC のトランザクション伝播はデフォルトで REQUIRED
 * (既存のトランザクションがあれば参加、無ければ新規作成) であるため、`R2dbcTxRunner.transactional { }`
 * が張った外側のトランザクションに、その中から呼ばれた `OrderRepositoryAdapter.save` が開く
 * トランザクションがそのまま「同じトランザクションへの参加」として正しくネストする
 * (OrderRepositoryAdapter.kt のクラス KDoc を参照)。もし2つの独立した `TransactionalOperator`
 * インスタンス (=それぞれ別の `ReactiveTransactionManager` に紐づく) を作ってしまうと、
 * この参加が起きず save が常に新しい独立したトランザクションを開始してしまう
 * (外側の失敗時にもコミットされてしまう不整合の温床になる)。
 */
class PersistenceBeanRegistrar :
    BeanRegistrarDsl({
        registerBean<OrderRepository> { OrderRepositoryAdapter(bean(), bean()) }
        registerBean<PaymentAttemptRepository> { PaymentAttemptRepositoryAdapter(bean()) }
        registerBean<SettlementRepository> { SettlementRepositoryAdapter(bean(), bean()) }
        registerBean<TxRunner> { R2dbcTxRunner(bean()) }
    })
