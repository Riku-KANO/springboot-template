package com.example.template.batch.settlement

import arrow.core.Either
import com.example.template.application.port.SettlementFilePort
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.SettlementRecord
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.Job
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.Step
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.infrastructure.item.ItemReader
import org.springframework.batch.infrastructure.item.ItemStreamReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.transaction.PlatformTransactionManager
import java.time.LocalDate

/**
 * 消込バッチ (`settlementReconciliationJob`) の Job / Step 定義。
 *
 * JobRepository と PlatformTransactionManager は Spring Boot の BatchAutoConfiguration
 * (spring-boot-starter-batch が DataSource を検出すると DefaultBatchConfiguration 経由で
 * 自動構成する) から注入される。:batch モジュール自身は JobRepository の実装
 * (JDBC ベース) を一切組み立てない — DataSource そのものは :bootstrap が用意する
 * composition root の責務である。
 *
 * ジョブパラメータ:
 * - `settlementDate` (LocalDate, 必須): 消込対象日。SettlementRecordItemReader が
 *   SettlementFilePort.readRecordsFor(date) に渡す。
 * - `taskToken` (String, 任意): Step Functions の `.waitForTaskToken` から払い出されたトークン。
 *   手動実行時は省略可能 (SettlementJobListener がコールバックをスキップする)。
 */
@Configuration
class SettlementReconciliationJobConfig(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
) {
    @Bean
    fun settlementReconciliationJob(
        settlementReconciliationStep: Step,
        settlementJobListener: SettlementJobListener,
    ): Job =
        JobBuilder("settlementReconciliationJob", jobRepository)
            .listener(settlementJobListener)
            .start(settlementReconciliationStep)
            .build()

    @Bean
    fun settlementReconciliationStep(
        settlementRecordItemReader: ItemReader<SettlementRecord>,
        settlementItemProcessor: SettlementItemProcessor,
        settlementItemWriter: SettlementItemWriter,
    ): Step =
        StepBuilder("settlementReconciliationStep", jobRepository)
            .chunk<SettlementRecord, Either<SettlementError, ReconciledSettlement>>(CHUNK_SIZE)
            .reader(settlementRecordItemReader)
            .processor(settlementItemProcessor)
            .writer(settlementItemWriter)
            .listener(settlementItemWriter)
            .transactionManager(transactionManager)
            .build()

    /**
     * @StepScope により、ステップ実行時まで生成を遅延させる (late binding)。これにより
     * `#{jobParameters['settlementDate']}` という SpEL で、実行時に渡された実際のジョブ
     * パラメータを注入できる (Bean 定義の評価タイミングではまだ値が確定していないため、
     * 通常の @Bean のようにコンストラクタで直接受け取ることはできない)。
     */
    @Bean
    @StepScope
    fun settlementRecordItemReader(
        settlementFilePort: SettlementFilePort,
        @Value("#{jobParameters['settlementDate']}") settlementDate: LocalDate,
    ): ItemStreamReader<SettlementRecord> = SettlementRecordItemReader(settlementFilePort, settlementDate)

    companion object {
        private const val CHUNK_SIZE = 20
    }
}
