-- ============================================================================
-- batch_settlement_results / batch_settlement_errors: 消込バッチ (:batch モジュールの
-- settlementReconciliationJob) 専用の実行結果テーブル。
--
-- これらは :application の SettlementRepository が永続化するドメインの正データ
-- (settlements / settlement_errors。V3/V4 参照) とは別物で、あくまで「このバッチジョブの
-- 実行監査ログ・再実行時の調査材料」という位置づけである (batch/.../SettlementBatchSchema.kt の
-- コメント参照)。
--
-- ##### なぜ Chunk 5 時点では :batch が CREATE TABLE IF NOT EXISTS で自己生成していたのか
-- :batch モジュールは :adapter-persistence に依存しない (settings.gradle.kts のモジュールグラフに
-- 辺が存在しない) ため、Chunk 5 の時点では Flyway マイグレーションを引けず、
-- SettlementItemWriter.beforeStep が自己完結的にテーブルを用意していた。
--
-- ##### このマイグレーションを追加した理由と、SettlementBatchSchema を削除しなかった理由
-- :bootstrap (composition root) は :adapter-persistence と :batch の両方に依存し、実運用では
-- Flyway が全スキーマ (Spring Batch のメタデータテーブル V0 も含む) を一元管理するのが筋である。
-- そこでこの V5 でテーブル定義を Flyway 側にも複製し、本番相当の環境ではここが正の管理場所になる。
-- 一方で batch/.../SettlementItemWriter.kt の `SettlementBatchSchema.ensureCreated(jdbcTemplate)`
-- 呼び出しはあえて残した。理由は2つ:
--   1. :batch は Flyway (:adapter-persistence) に依存できないモジュールグラフ上の制約は今も
--      変わっておらず、:batch 単体でジョブを起動する構成 (Chunk 5 の
--      SettlementReconciliationJobIntegrationTest がまさにそれ) では、この V5 マイグレーションは
--      そもそも適用されない。自己生成を消してしまうと :batch 単体では二度とテーブルが
--      作られなくなり、:batch:test が壊れる (このテストは各テスト前に DROP TABLE してから
--      beforeStep での再生成に依存している)。
--   2. `CREATE TABLE IF NOT EXISTS` は完全に冪等なので、:bootstrap 経由の本番相当環境で
--      Flyway がこの V5 を先に適用していても、ジョブ実行時に beforeStep が呼ぶ
--      `ensureCreated` は単なる no-op になるだけで実害が無い。
-- 結果として「:bootstrap 経由の実運用では Flyway が唯一の真実の情報源として先に生成し、
-- :batch 単体運用 (テスト等) では従来通りの自己生成にフォールバックする」という二段構えになる。
-- ============================================================================
CREATE TABLE batch_settlement_results (
    id                       BIGSERIAL     NOT NULL PRIMARY KEY,
    order_id                 VARCHAR(64)   NOT NULL,
    provider_transaction_id  VARCHAR(128)  NOT NULL,
    settled_amount_minor     BIGINT        NOT NULL,
    currency                 VARCHAR(3)    NOT NULL,
    settled_at               TIMESTAMPTZ   NOT NULL,
    outcome                  VARCHAR(32)   NOT NULL,
    expected_amount_minor    BIGINT,
    actual_amount_minor      BIGINT,
    recorded_at              TIMESTAMPTZ   NOT NULL
);

CREATE TABLE batch_settlement_errors (
    id            BIGSERIAL     NOT NULL PRIMARY KEY,
    order_id      VARCHAR(64),
    error_type    VARCHAR(32)   NOT NULL,
    message       VARCHAR(1000) NOT NULL,
    recorded_at   TIMESTAMPTZ   NOT NULL
);
