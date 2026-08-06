package com.example.template.adapter.persistence.tx

import arrow.core.Either
import com.example.template.adapter.persistence.testsupport.PostgresIntegrationTest
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import java.util.UUID

/**
 * [R2dbcTxRunner] のロールバック機構そのものを検証する。
 *
 * ##### なぜこのテストが必要か
 * `TransactionalOperator.executeAndAwait` は *例外を投げた場合にしか* ロールバックしない。
 * :application 層のユースケースは例外を投げず `Either.Left` で失敗を表現するため、
 * 何も対策しなければ「block が Left を返してもコミットされてしまう」というバグが
 * 起こりうる (これは `R2dbcTxRunner` の KDoc に書いた通り、素朴な `executeAndAwait` の
 * 呼び出しだけでは再現しやすい落とし穴)。[R2dbcTxRunner] は block の結果が Left だった場合に
 * `ReactiveTransaction.setRollbackOnly()` を呼ぶことでこれを防いでいるが、それが本当に
 * 機能しているかどうかは「実際に行を書き込んでから Left を返し、コミット後にその行が
 * 消えている」ことでしか証明できない。これが課題文で名指しされているロールバックテスト。
 */
class R2dbcTxRunnerTest : PostgresIntegrationTest() {
    private val txRunner by lazy { R2dbcTxRunner(transactionalOperator) }

    // Order 集約全体を経由せず、トランザクション境界の挙動だけを見たいので、
    // orders テーブルへの最小限の INSERT を直接発行する。
    private suspend fun insertMinimalOrder(id: String) {
        databaseClient
            .sql(
                """
                INSERT INTO orders (id, customer_id, recipient_name, postal_code, prefecture, city, address_line1, status_type)
                VALUES (:id, 'customer-1', 'Name', '100-0001', 'Tokyo', 'Chiyoda', '1-1', 'DRAFT')
                """.trimIndent(),
            ).bind("id", id)
            .fetch()
            .awaitRowsUpdated()
    }

    private suspend fun orderExists(id: String): Boolean =
        databaseClient
            .sql("SELECT id FROM orders WHERE id = :id")
            .bind("id", id)
            .map { row, _ -> row.get("id", String::class.java) ?: "" }
            .awaitOneOrNull() != null

    @Test
    fun `a Left result rolls back writes performed inside the block`() =
        runTest {
            val id = "tx-rollback-${UUID.randomUUID()}"

            val result: Either<String, Unit> =
                txRunner.transactional {
                    insertMinimalOrder(id)
                    Either.Left("business failure after the write")
                }

            // Left の中身がそのまま呼び出し元に返ってきていること (rollback-only 化しても
            // 戻り値は変わらない、という R2dbcTxRunner の前提を確認する)。
            assertEquals(Either.Left("business failure after the write"), result)
            // かつ、コミットされずロールバックされたことで行が実際に残っていないこと。
            assertEquals(false, orderExists(id))
        }

    @Test
    fun `a Right result commits writes performed inside the block`() =
        runTest {
            val id = "tx-commit-${UUID.randomUUID()}"

            val result: Either<String, Unit> =
                txRunner.transactional {
                    insertMinimalOrder(id)
                    Either.Right(Unit)
                }

            assertEquals(Either.Right(Unit), result)
            assertEquals(true, orderExists(id))
        }
}
