package com.example.template.bootstrap.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import javax.sql.DataSource

/**
 * JDBC 用 `DataSource` (Flyway のマイグレーション、Spring Batch 6 の `JobRepository` が使う。
 * R2DBC 版の `JobRepository` が存在しないため) を組み立てる Bean 定義。
 *
 * ##### なぜここだけ手で `DataSource` を組み立てる必要があるのか (実機で踏んだ地雷)
 * 当初は `spring.datasource.*` プロパティを application-*.yml に置くだけで、Boot の
 * `DataSourceAutoConfiguration` が自動的に `DataSource` を作ってくれるはずだと考えていた
 * (R2DBC 側の `ConnectionFactory` が `R2dbcAutoConfiguration` によって全く独立に自動生成される
 * のと対称的に)。しかし実機で `--debug` を付けて起動し条件評価レポートを確認したところ、
 * `org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration` 自体が
 * `@ConditionalOnMissingBean(ConnectionFactory.class)` を持っており、R2DBC の
 * `ConnectionFactory` Bean が (このアプリのように) 既に存在する場合は **丸ごと** 発火しない
 * ことが判明した (「JDBC と R2DBC の両方を同時に自動構成すると意図しない二重構成になりかねない」
 * という Boot 側の安全側の判断と思われる)。これは Chunk 6 依頼時点の想定
 * ("spring.datasource.* を置くだけで Boot 標準の自動構成に任せられる") が実機の挙動と
 * 食い違っていた箇所であり、本チャンクで実際に検証して初めて判明した。
 *
 * そのため、Boot が内部で `DataSourceConfiguration.Hikari` に対して行っているのと全く同じ
 * 手順 (`spring.datasource.*` を `DataSourceProperties` にバインドし、
 * `initializeDataSourceBuilder()` でプールする DataSource 実装 (`HikariDataSource`、
 * spring-boot-starter-jdbc 経由でクラスパス上に存在する唯一のプール実装なので自動選択される)
 * を組み立てる) をここで手動により再現する。R2DBC 側の `ConnectionFactory` /
 * `ReactiveTransactionManager` はこれとは無関係に `R2dbcAutoConfiguration` /
 * `R2dbcTransactionManagerAutoConfiguration` が引き続き自動構成してくれるため、
 * 変更が必要なのは JDBC 側のこの1箇所だけで済む。
 *
 * この `DataSource` Bean が登録されることで、`DataSourceTransactionManagerAutoConfiguration`
 * (`@ConditionalOnSingleCandidate(DataSource.class)`) が正しく発火して JDBC 用
 * `PlatformTransactionManager` を自動生成し、Spring Batch の `JobRepository`/`JobOperator`
 * (:batch の `SettlementReconciliationJobConfig` がコンストラクタで要求する) がそれを
 * 解決できるようになる。
 */
@Configuration
class JdbcDataSourceConfig {
    @Bean
    @ConfigurationProperties(prefix = "spring.datasource")
    fun dataSourceProperties(): DataSourceProperties = DataSourceProperties()

    @Bean
    fun dataSource(dataSourceProperties: DataSourceProperties): DataSource = dataSourceProperties.initializeDataSourceBuilder().build()
}
