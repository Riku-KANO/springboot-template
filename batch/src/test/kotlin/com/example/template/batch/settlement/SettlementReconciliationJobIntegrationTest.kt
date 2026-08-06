package com.example.template.batch.settlement

import arrow.core.Either
import com.example.template.application.port.SettlementFilePort
import com.example.template.application.settlement.ReconcileSettlementRecord
import com.example.template.application.settlement.SettlementVerdict
import com.example.template.batch.SettlementBatchTestApplication
import com.example.template.batch.settlement.testsupport.FakeReconcileSettlementRecord
import com.example.template.batch.settlement.testsupport.FakeSettlementFilePort
import com.example.template.batch.settlement.testsupport.RecordingTaskCallbackPort
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.ReconciliationOutcome
import com.example.template.domain.settlement.SettlementRecord
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.batch.core.BatchStatus
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.parameters.JobParametersBuilder
import org.springframework.batch.core.launch.JobOperator
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.time.LocalDate
import java.util.Currency

/**
 * settlementReconciliationJob をジョブ起動 (JobOperator.start) から実行結果の DB 反映、
 * Step Functions へのコールバックまで、実際の PostgreSQL に対してエンドツーエンドで検証する。
 *
 * :batch は :adapter-persistence にも :adapter-messaging にも依存しないため、SettlementFilePort /
 * ReconcileSettlementRecord / TaskCallbackPort は全てこのテスト専用の Fake に差し替える
 * (testsupport/Fakes.kt 参照)。判定ロジックそのもの (domain の reconcile 関数) の正しさは
 * domain/application の各テストで既に検証済みであり、ここで確認したいのは
 * 「バッチのチャンク処理・skip-and-report・ジョブリスナーのコールバックが結線として正しく動くか」である。
 *
 * Spring Batch のメタデータスキーマ (BATCH_JOB_INSTANCE 等) は、Chunk 3 が本番用に追加する予定の
 * Flyway マイグレーション (V0__batch_schema.sql) には依存せず、spring-batch-core が同梱する
 * schema-postgresql.sql を spring.sql.init.schema-locations 経由で読み込んで自己完結的に用意する
 * (Spring Boot 4.1 は Batch 用の自動スキーマ初期化機能を持たないため、汎用の SQL 初期化の仕組みを使う)。
 */
@Testcontainers
@SpringBootTest(classes = [SettlementBatchTestApplication::class])
@Import(SettlementReconciliationJobIntegrationTest.TestBeans::class)
class SettlementReconciliationJobIntegrationTest {
    @Autowired
    private lateinit var jobOperator: JobOperator

    @Autowired
    @Qualifier("settlementReconciliationJob")
    private lateinit var settlementReconciliationJob: Job

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var recordingTaskCallbackPort: RecordingTaskCallbackPort

    /**
     * Spring のテストコンテキストはクラス内の全テストメソッドで使い回される (同じ PostgreSQL・
     * 同じ JdbcTemplate) ため、あるテストが書き込んだ行が別テストの count(*) 系アサーションに
     * 混入しないよう、各テストの実行前に前回分のテーブルを消しておく。
     * (SettlementItemWriter.beforeStep が CREATE TABLE IF NOT EXISTS で毎回作り直すため、
     * ここでは DROP するだけでよい。)
     */
    @BeforeEach
    fun resetTables() {
        jdbcTemplate.execute("DROP TABLE IF EXISTS batch_settlement_results")
        jdbcTemplate.execute("DROP TABLE IF EXISTS batch_settlement_errors")
    }

    @Test
    fun `正常レコードは settlements へ、失敗レコードは errors へ書き込まれジョブは完了しコールバックされる`() {
        val settlementDate = LocalDate.of(2026, 8, 5)
        val jobParameters =
            JobParametersBuilder()
                .addLocalDate("settlementDate", settlementDate)
                .addString("taskToken", "task-token-mixed")
                .toJobParameters()

        val execution = jobOperator.start(settlementReconciliationJob, jobParameters)

        assertEquals(BatchStatus.COMPLETED, execution.status)

        val resultCount = jdbcTemplate.queryForObject("select count(*) from batch_settlement_results", Int::class.java)
        val errorCount = jdbcTemplate.queryForObject("select count(*) from batch_settlement_errors", Int::class.java)
        assertEquals(2, resultCount)
        assertEquals(1, errorCount)

        val outcomes = jdbcTemplate.queryForList("select outcome from batch_settlement_results order by outcome").map { it["outcome"] }
        assertEquals(listOf("AMOUNT_MISMATCH", "MATCHED"), outcomes)

        val errorTypes = jdbcTemplate.queryForList("select error_type from batch_settlement_errors").map { it["error_type"] }
        assertEquals(listOf("UNKNOWN_ORDER"), errorTypes)

        val (taskToken, payload) = requireNotNull(recordingTaskCallbackPort.lastSuccess) { "notifySuccess should have been called" }
        assertEquals("task-token-mixed", taskToken.value)
        val report = payload as com.example.template.application.settlement.SettlementReport
        assertEquals(1, report.matchedCount)
        assertEquals(1, report.mismatchCount)
        assertEquals(1, report.failedCount)
        // failedCount > 0 が最優先されるため、不一致もあるが verdict は Failed になる (SettlementReport の仕様どおり)。
        assertInstanceOf(SettlementVerdict.Failed::class.java, report.verdict)
        assertNull(recordingTaskCallbackPort.lastFailure)
    }

    @Test
    fun `taskToken が無いジョブ実行はコールバックされない`() {
        val settlementDate = LocalDate.of(2026, 8, 6)
        val jobParameters = JobParametersBuilder().addLocalDate("settlementDate", settlementDate).toJobParameters()

        val execution = jobOperator.start(settlementReconciliationJob, jobParameters)

        assertEquals(BatchStatus.COMPLETED, execution.status)
        assertNull(recordingTaskCallbackPort.lastSuccess)
        assertNull(recordingTaskCallbackPort.lastFailure)
    }

    @TestConfiguration
    class TestBeans {
        private val jpy: Currency = Currency.getInstance("JPY")

        private fun record(
            orderId: String,
            amountMinor: Long,
            txnId: String,
        ): SettlementRecord =
            SettlementRecord(
                orderId = OrderId.create(orderId).shouldBeRight(),
                settledAmount = Money(MoneyMinor.create(amountMinor).shouldBeRight(), jpy),
                settledAt = Instant.parse("2026-08-05T00:00:00Z"),
                providerTransactionId = txnId,
            )

        @Bean
        fun settlementFilePort(): SettlementFilePort {
            val matched = record("order-matched", 1_000, "txn-matched")
            val mismatch = record("order-mismatch", 999, "txn-mismatch")
            val unknown = record("order-unknown", 1_000, "txn-unknown")
            return FakeSettlementFilePort(
                mapOf(
                    LocalDate.of(2026, 8, 5) to listOf(matched, mismatch, unknown),
                    LocalDate.of(2026, 8, 6) to listOf(matched),
                ),
            )
        }

        @Bean
        fun reconcileSettlementRecord(): ReconcileSettlementRecord =
            FakeReconcileSettlementRecord(
                mapOf(
                    "order-matched" to Either.Right(ReconciliationOutcome.Matched),
                    "order-mismatch" to
                        Either.Right(
                            ReconciliationOutcome.AmountMismatch(
                                expected = Money(MoneyMinor.create(1_000).shouldBeRight(), jpy),
                                actual = Money(MoneyMinor.create(999).shouldBeRight(), jpy),
                            ),
                        ),
                    "order-unknown" to Either.Left(SettlementError.UnknownOrder(OrderId.create("order-unknown").shouldBeRight())),
                ),
            )

        @Bean
        fun recordingTaskCallbackPort(): RecordingTaskCallbackPort = RecordingTaskCallbackPort()
    }

    companion object {
        @Container
        @JvmStatic
        private val postgres: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))

        @DynamicPropertySource
        @JvmStatic
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            // Spring Boot 4.1 は Spring Batch 用の自動スキーマ初期化機能を持たないため
            // (spring.batch.jdbc.initialize-schema は本番設定として README/報告に残すのみ)、
            // 汎用の SQL 初期化の仕組みで spring-batch-core 同梱の DDL を読み込む。
            registry.add("spring.sql.init.mode") { "always" }
            registry.add("spring.sql.init.schema-locations") { "classpath:org/springframework/batch/core/schema-postgresql.sql" }
            // このテストは自前の JobOperator.start 呼び出しでジョブを起動するため、
            // コンテキスト起動時の自動ジョブ実行 (JobLauncherApplicationRunner) は無効化する。
            registry.add("spring.batch.job.enabled") { "false" }
        }
    }
}
