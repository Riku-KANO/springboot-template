package com.example.template.batch.settlement

import org.springframework.jdbc.core.JdbcTemplate

/**
 * このバッチジョブ専用の実行結果テーブル (settlements 成功分 / errors 失敗分) の DDL。
 *
 * これらのテーブルは :application の SettlementRepository が永続化するドメインの正データ
 * (消込結果そのもの。ReconcileSettlementRecord ユースケースが TxRunner 越しに書き込む) とは
 * 別物であり、あくまで「このバッチジョブの実行監査ログ・再実行時の調査材料」という位置づけである。
 * ドメインの正データは :adapter-persistence (Chunk 3) が管理する実際の永続化ストアに既に
 * 書き込まれている (SettlementItemProcessor が呼ぶ ReconcileSettlementRecord の内部で完結する)。
 *
 * :batch は :adapter-persistence の Flyway マイグレーションに依存しない
 * (モジュールグラフ上そもそも依存関係がない) ため、このバッチ固有のテーブルは自己完結的に
 * IF NOT EXISTS で用意する。本番運用でスキーマ管理を一元化したい場合は、Chunk 6 が
 * このテーブルを Flyway 側の管理下に移し、ここでの自動作成を無効化してもよい
 * (詳細はチャット越しの報告を参照)。
 */
object SettlementBatchSchema {
    private const val CREATE_RESULTS_TABLE =
        """
        CREATE TABLE IF NOT EXISTS batch_settlement_results (
            id BIGSERIAL PRIMARY KEY,
            order_id VARCHAR(64) NOT NULL,
            provider_transaction_id VARCHAR(128) NOT NULL,
            settled_amount_minor BIGINT NOT NULL,
            currency VARCHAR(3) NOT NULL,
            settled_at TIMESTAMP WITH TIME ZONE NOT NULL,
            outcome VARCHAR(32) NOT NULL,
            expected_amount_minor BIGINT,
            actual_amount_minor BIGINT,
            recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
        )
        """

    private const val CREATE_ERRORS_TABLE =
        """
        CREATE TABLE IF NOT EXISTS batch_settlement_errors (
            id BIGSERIAL PRIMARY KEY,
            order_id VARCHAR(64),
            error_type VARCHAR(32) NOT NULL,
            message VARCHAR(1000) NOT NULL,
            recorded_at TIMESTAMP WITH TIME ZONE NOT NULL
        )
        """

    fun ensureCreated(jdbcTemplate: JdbcTemplate) {
        jdbcTemplate.execute(CREATE_RESULTS_TABLE)
        jdbcTemplate.execute(CREATE_ERRORS_TABLE)
    }
}
