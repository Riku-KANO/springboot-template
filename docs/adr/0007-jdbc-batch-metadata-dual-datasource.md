# ADR 0007: Spring Batch のメタデータは JDBC で持ち、R2DBC とデュアル接続にする

## ステータス

Accepted

## コンテキスト

このテンプレートのアプリ本体 (Web API、`:adapter-persistence`) は R2DBC でノンブロッキングに
PostgreSQL へアクセスする (ADR 0001)。一方 Spring Batch 6 の `JobRepository`
(ジョブ実行のメタデータ: `BATCH_JOB_INSTANCE`/`BATCH_STEP_EXECUTION` 等を管理する) は
JDBC ベースの実装しか存在せず、R2DBC 版の `JobRepository` は提供されていない。

## 決定

同一の PostgreSQL インスタンスに対して、JDBC (`DataSource`, HikariCP) と R2DBC
(`ConnectionFactory`) の両方の接続経路を持つ「デュアル DataSource/ConnectionFactory」構成にする。

- JDBC: Flyway のマイグレーション実行、Spring Batch 6 の `JobRepository`、
  `SettlementItemWriter`/`SettlementRecordItemReader` 等バッチ内の直接の JDBC アクセスが使う。
- R2DBC: `:adapter-persistence` (Web API の実行時クエリ経路) が使う。

Spring Boot の `DataSourceAutoConfiguration` は `@ConditionalOnMissingBean(ConnectionFactory::class)`
を持っており、R2DBC の `ConnectionFactory` Bean が既に存在する場合は **丸ごと発火しない**
(実機の `--debug` 条件評価レポートで確認済み)。そのため JDBC 用 `DataSource` だけは
`:bootstrap` の `JdbcDataSourceConfig` が `DataSourceProperties` を手動でバインドして組み立てる
(`DataSourceConfiguration.Hikari` が内部で行っているのと同じ手順を再現する)。R2DBC 側の
`ConnectionFactory`/`ReactiveTransactionManager` は従来通り自動構成に任せる。

Spring Batch 6 のメタデータテーブル (`BATCH_JOB_INSTANCE` 等) は、Spring Boot 4.1 の
バッチ自動構成モジュール (`org.springframework.boot:spring-boot-batch`) の `BatchProperties`
クラスを実機で `javap` 確認したところ、**`jdbc.initialize-schema` に相当するプロパティ自体が
存在しない** (`job.name` の1プロパティしか持たない) ことが判明した。Spring Boot 3.x 系までの
`spring.batch.jdbc.initialize-schema` のような「Boot 側でテーブルを自動作成する」機能そのものが
Batch 6 / Boot 4.1 の自動構成から無くなっている。したがってこのテンプレートでは、
`spring-batch-core:6.0.4` の jar に同梱される `schema-postgresql.sql` を一字一句そのまま
`adapter-persistence/.../db/migration/V0__batch_schema.sql` として Flyway 管理下に置き、
Flyway を Batch メタデータテーブルの唯一の作成経路にしている。

## 帰結

**良い点**

- 「本番相当の環境では Flyway が全スキーマ (アプリのテーブルと Batch のメタデータテーブルの
  両方) を一元管理する」という単純なメンタルモデルを維持できる。
- Boot 4.1 で Batch の自動スキーマ初期化機能そのものが無くなっている、という事実を
  実機確認済みなので、「新しいバージョンでは `initialize-schema=never` を設定すべきでは」
  という誤った"親切心"での変更を防げる (そもそも設定するプロパティが存在しない)。

**トレードオフ・注意点**

- `V0__batch_schema.sql` は `spring-batch-core` の jar から抜粋したものであり、Spring Batch を
  アップグレードする際は同じファイルを新しい jar から改めて丸ごと抜き出して置き換える必要がある
  (差分編集はしないこと --- ファイル冒頭のコメントに明記)。
- `:batch` モジュールは (モジュールグラフ上の制約により) `:adapter-persistence` に依存できず
  Flyway を引けないため、`:batch` 単体でジョブを起動するテスト/構成
  (`SettlementReconciliationJobIntegrationTest`) では、`SettlementItemWriter.beforeStep` が
  `CREATE TABLE IF NOT EXISTS` で自己完結的にバッチ専用テーブル (`batch_settlement_results`/
  `batch_settlement_errors`) を用意する経路が今も残っている。`:bootstrap` 経由の本番相当環境では
  Flyway (`V5__create_batch_settlement_tables.sql`) が先に同じテーブルを作るため、
  `ensureCreated` は単なる no-op になる --- 「実運用では Flyway が正、`:batch` 単体運用では
  自己生成にフォールバックする」という二段構えである点を、Batch 関連のマイグレーションを
  触る際は必ず意識すること。
