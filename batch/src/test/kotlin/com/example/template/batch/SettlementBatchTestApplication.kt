package com.example.template.batch

import org.springframework.boot.autoconfigure.SpringBootApplication

/**
 * :batch モジュール単体のジョブ統合テストのための最小 Spring Boot アプリケーション。
 * このパッケージ (com.example.template.batch) をルートにすることで、
 * com.example.template.batch.settlement 配下の @Component/@Configuration
 * (SettlementReconciliationJobConfig, SettlementItemProcessor, SettlementItemWriter,
 * SettlementJobListener) がコンポーネントスキャンで自動的に拾われる。
 *
 * :bootstrap (Chunk 6) が実際にどうコンポーネントスキャンを構成する必要があるかは別問題であり
 * (:bootstrap の @SpringBootApplication は com.example.template.bootstrap パッケージにあり、
 * デフォルトのスキャン範囲は com.example.template.adapter.messaging や com.example.template.batch
 * まで届かない)、この点はチャット越しの報告で明示する。
 */
@SpringBootApplication
class SettlementBatchTestApplication
