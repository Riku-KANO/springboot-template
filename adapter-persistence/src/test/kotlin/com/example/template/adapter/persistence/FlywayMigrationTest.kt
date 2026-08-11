package com.example.template.adapter.persistence

import com.example.template.adapter.persistence.testsupport.PostgresIntegrationTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.awaitOneOrNull

/**
 * Flyway が V0〜V8 の9本のマイグレーションを、空の DB に対して過不足なく適用できることの確認。
 *
 * [PostgresIntegrationTest] の companion object 初期化ブロックで既に `migrate()` が
 * 一度だけ実行されている (シングルトンコンテナパターン)。そこで例外が起きていれば
 * このテストクラスに限らずスイート全体が起動時に失敗するはずだが、
 * 「本当に9本とも成功として記録されているか」を明示的に確認する専用テストとして分離した。
 *
 * V5 (batch_settlement_results / batch_settlement_errors) は Chunk 6 (:bootstrap) が、
 * :batch モジュールが自己生成していたバッチ実行結果テーブルを Flyway の一元管理下に
 * 移すために追加した (V5__create_batch_settlement_tables.sql のコメントを参照)。
 */
class FlywayMigrationTest : PostgresIntegrationTest() {
    @Test
    fun `all nine migrations (V0 through V8) were applied successfully`() =
        runTest {
            val appliedCount =
                databaseClient
                    .sql("SELECT COUNT(*) AS cnt FROM flyway_schema_history WHERE success = true")
                    .map { row, _ -> row.get("cnt", Long::class.javaObjectType) ?: 0L }
                    .awaitOneOrNull()
            assertEquals(9L, appliedCount)
        }
}
