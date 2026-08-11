package com.example.template.adapter.persistence.testsupport

import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryOptions
import org.flywaydb.core.Flyway
import org.springframework.r2dbc.connection.R2dbcTransactionManager
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.transaction.reactive.TransactionalOperator
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * 統合テストの共通基盤: PostgreSQL の Testcontainer を「テストスイート全体で1個だけ」起動し、
 * Flyway で本物のマイグレーション (V0〜V8) を空の DB に適用してから各テストへ渡す。
 *
 * ##### シングルトンコンテナパターンを採用した理由
 * JUnit5 の `@Testcontainers` + `@Container` (companion object 上の static フィールド) を
 * 使うと、コンテナのライフサイクルは「そのテストクラス単位」になる
 * (クラスごとに起動・終了する)。しかし本テストスイートには
 * OrderRepositoryAdapterTest / SettlementRepositoryAdapterTest / R2dbcTxRunnerTest など
 * 複数のテストクラスがあり、クラスごとにコンテナを起動し直すと起動コストが積み重なる。
 * そこで `@Testcontainers` 拡張を使わず、companion object の初期化ブロックで手動で
 * 1回だけ `start()` する素朴な static フィールドとして持たせている。この基底クラスを
 * 継承するテストクラスはすべて同一の (JVM 起動から終了までの間ずっと生きている) コンテナと
 * 同一のコネクションファクトリを共有する。`stop()` を明示的に呼んでいないのは、
 * Testcontainers 内蔵の Ryuk リソースリーパーが JVM 終了時にコンテナを片付けてくれるため。
 *
 * ##### JDBC と R2DBC を両方使っている理由
 * Flyway 自体は JDBC 経由でしかマイグレーションを実行できない (R2DBC 対応が無い) ため、
 * ここだけ `postgres.jdbcUrl` を使う。アプリ本体の実行時経路 (R2DBC) とは別物であり、
 * :bootstrap 側の composition root でも同様に「JDBC (Flyway 用 / Spring Batch JobRepository
 * 用) と R2DBC (アプリのクエリ用) の両方の DataSource/ConnectionFactory を用意する」
 * 必要がある点に注意 (詳細は本チャンクの報告を参照)。
 *
 * (`@EnabledIfDockerAvailable` はクラス継承 + companion object 初期化という本基盤の構成とは
 * 相性が悪く [org.testcontainers.junit.jupiter.EnabledIfDockerAvailableCondition] が
 * `ExtensionContext` に test class を見つけられずエラーになったため採用していない。
 * Docker が使えない環境では companion object の初期化自体が例外で失敗し、
 * そのままテスト失敗として報告される)。
 */
abstract class PostgresIntegrationTest {
    protected val connectionFactory: ConnectionFactory get() = Companion.connectionFactory

    protected val databaseClient: DatabaseClient by lazy { DatabaseClient.create(connectionFactory) }

    protected val transactionalOperator: TransactionalOperator by lazy {
        TransactionalOperator.create(R2dbcTransactionManager(connectionFactory))
    }

    companion object {
        private val postgres: PostgreSQLContainer =
            PostgreSQLContainer("postgres:17-alpine").apply { start() }

        val connectionFactory: ConnectionFactory =
            ConnectionFactories.get(
                ConnectionFactoryOptions
                    .builder()
                    .option(ConnectionFactoryOptions.DRIVER, "postgresql")
                    .option(ConnectionFactoryOptions.HOST, postgres.host)
                    .option(ConnectionFactoryOptions.PORT, postgres.firstMappedPort)
                    .option(ConnectionFactoryOptions.DATABASE, postgres.databaseName)
                    .option(ConnectionFactoryOptions.USER, postgres.username)
                    .option(ConnectionFactoryOptions.PASSWORD, postgres.password)
                    .build(),
            )

        init {
            Flyway
                .configure()
                .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
                .load()
                .migrate()
        }
    }
}
