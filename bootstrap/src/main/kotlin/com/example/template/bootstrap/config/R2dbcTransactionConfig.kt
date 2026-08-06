package com.example.template.bootstrap.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.ReactiveTransactionManager
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * R2DBC の `TransactionalOperator` を組み立てる Bean 定義。
 *
 * ##### デュアル DataSource / ConnectionFactory 構成そのものはオートコンフィグに任せる
 * :bootstrap は JDBC 用 `DataSource` (Flyway のマイグレーション、Spring Batch 6 の
 * `JobRepository` が使う。R2DBC 版が存在しないため) と R2DBC 用 `ConnectionFactory`
 * (アプリのクエリ経路) の両方を用意する必要があるが、そのどちらも Bean 定義を1行も書かない。
 * `spring.datasource.*` / `spring.r2dbc.*` プロパティを application-*.yml 側に置くだけで、
 * `DataSourceAutoConfiguration` (JDBC, :batch が持ち込む spring-boot-starter-jdbc 経由) と
 * `R2dbcAutoConfiguration` (R2DBC, :adapter-persistence が持ち込む spring-boot-starter-data-r2dbc +
 * r2dbc-postgresql 経由) がそれぞれ独立に `DataSource` / `ConnectionFactory` を生成してくれる。
 * 同様に `PlatformTransactionManager` (JDBC 用、Spring Batch の JobRepository が使う) は
 * `DataSourceTransactionManagerAutoConfiguration` が、`ReactiveTransactionManager` (R2DBC 用) は
 * `R2dbcTransactionManagerAutoConfiguration` (Bean 名 `connectionFactoryTransactionManager`) が
 * それぞれ自動生成する。2種類の DataSource 系 Bean (`DataSource` と `ConnectionFactory`) が
 * 同時に存在しても型が異なるため、Spring Batch の `PlatformTransactionManager` 注入や
 * `@Transactional` の解決で衝突することはない (実機の spring-boot-jdbc / spring-boot-r2dbc の
 * jar を javap で確認して各オートコンフィグレーションクラスの存在を確認済み)。
 *
 * ##### `TransactionalOperator` だけはここで手で組み立てる
 * 上記のとおり `ReactiveTransactionManager` まではオートコンフィグされるが、それを
 * `TransactionalOperator.create(...)` でラップした Bean は Spring Boot のどのオートコンフィグ
 * レーションも提供しない (`spring-boot-transaction` / `spring-boot-r2dbc` の各
 * `*AutoConfiguration` クラスを javap で確認したが、`TransactionalOperator` を返す Bean 定義は
 * 存在しなかった — `TransactionTemplate` [ブロッキング版] の Bean 定義はあるが、リアクティブ版の
 * 対応物は無い)。そのためここだけ、オートコンフィグされた `ReactiveTransactionManager` を
 * 受け取って薄くラップするだけの `@Configuration` を書く。
 *
 * ##### `OrderRepositoryAdapter` と `R2dbcTxRunner` が同じ Bean を共有する仕組み
 * ここで作る Bean はデフォルトでシングルトンスコープなので、config/PersistenceBeans.kt の
 * `beans { }` DSL 内で `ref<TransactionalOperator>()` を (`OrderRepositoryAdapter` のコンストラクタと
 * `R2dbcTxRunner` のコンストラクタで) 2回呼んでも、同一のインスタンスが解決される。
 */
@Configuration
class R2dbcTransactionConfig {
    @Bean
    fun transactionalOperator(reactiveTransactionManager: ReactiveTransactionManager): TransactionalOperator =
        TransactionalOperator.create(reactiveTransactionManager)
}
