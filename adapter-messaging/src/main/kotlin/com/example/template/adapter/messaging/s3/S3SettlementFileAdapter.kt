package com.example.template.adapter.messaging.s3

import arrow.core.Either
import arrow.core.raise.either
import arrow.core.raise.ensure
import com.example.template.application.port.SettlementFilePort
import com.example.template.domain.error.SettlementError
import com.example.template.domain.settlement.SettlementRecord
import io.awspring.cloud.s3.S3Template
import jakarta.validation.constraints.NotBlank
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import org.springframework.stereotype.Component
import org.springframework.validation.annotation.Validated
import java.io.BufferedReader
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 消込ファイル格納先の S3 バケット/キー接頭辞。ハードコードせずプロパティ駆動にすることで、
 * 環境 (ローカル LocalStack / 本番 AWS) ごとに application.yml やコンテナの環境変数だけで
 * 切り替えられるようにする (`settlement.s3.bucket-name` は必須、`settlement.s3.key-prefix` は
 * 省略時 "settlements" を既定値とする)。
 */
@ConfigurationProperties(prefix = "settlement.s3")
@Validated
data class SettlementS3Properties(
    @field:NotBlank val bucketName: String,
    @field:NotBlank val keyPrefix: String = "settlements",
)

/**
 * [SettlementS3Properties] を有効化するための Configuration。
 * :bootstrap 側のコンポーネントスキャンがこのパッケージ (com.example.template.adapter.messaging)
 * に到達している前提 (詳細はチャット越しの報告を参照)。
 */
@Configuration
@EnableConfigurationProperties(SettlementS3Properties::class)
class SettlementS3PropertiesConfig

/**
 * [SettlementFilePort] の S3 実装。
 *
 * 消込ファイルは `{keyPrefix}/{yyyy-MM-dd}.csv` に1日1ファイル置かれる前提とし、1行を
 * `orderId,settledAmountMinor,currency,settledAt,providerTransactionId` のカンマ区切りとする
 * (テンプレートとしての最小実装。引用符付きフィールドや埋め込みカンマを扱う本格的な CSV
 * パーサではない。実運用では Apache Commons CSV 等への差し替えを検討すること)。
 *
 * S3Template.download は同期 (ブロッキング) API である。Spring Cloud AWS の S3AutoConfiguration は
 * 既定では S3AsyncClient を構成しない (CRT ベースの非同期クライアントには別途 AWS CRT 依存が要る) ため、
 * ここでは素朴に同期 S3Client 経由の S3Template を使い、withContext(Dispatchers.IO) でブロッキング
 * 呼び出しを I/O 専用ディスパッチャへ退避させることでイベントループスレッドの占有を避ける
 * (SfnTaskCallbackAdapter は SfnAsyncClient が真に非同期な CompletableFuture を返すため `.await()`
 * で済むが、こちらは同期 API しか手元にないための次善策、という違いがある)。
 *
 * 1行単位のパース失敗もファイル全体の Left とする。壊れた行をログだけに残して処理済みにすると、
 * 再実行しても永久に消込されないデータ欠落になるためである。
 */
@Component
class S3SettlementFileAdapter(
    private val s3Template: S3Template,
    private val properties: SettlementS3Properties,
) : SettlementFilePort {
    override suspend fun readRecordsFor(date: LocalDate): Either<SettlementError, List<SettlementRecord>> =
        withContext(Dispatchers.IO) {
            either {
                val key = "${properties.keyPrefix}/${date.format(DATE_FORMAT)}.csv"
                val location = "s3://${properties.bucketName}/$key"

                val resource =
                    Either
                        .catch { s3Template.download(properties.bucketName, key) }
                        .mapLeft { throwable -> SettlementError.InfrastructureFailure("failed to access $location: ${throwable.message}") }
                        .bind()

                ensure(resource.exists()) { SettlementError.InfrastructureFailure("settlement file not found: $location") }

                val lines =
                    Either
                        .catch { resource.inputStream.bufferedReader(Charsets.UTF_8).use(BufferedReader::readLines) }
                        .mapLeft { throwable -> SettlementError.InfrastructureFailure("failed to read $location: ${throwable.message}") }
                        .bind()

                lines.filter(String::isNotBlank).map { line -> parseLine(line).bind() }
            }
        }

    private fun parseLine(line: String): Either<SettlementError, SettlementRecord> {
        val fields = line.split(",")
        return if (fields.size != EXPECTED_FIELD_COUNT) {
            Either.Left(SettlementError.MalformedRecord("expected $EXPECTED_FIELD_COUNT comma-separated fields, got ${fields.size}"))
        } else {
            SettlementRecord.parse(
                rawOrderId = fields[0],
                rawSettledAmountMinor = fields[1],
                rawCurrency = fields[2],
                rawSettledAt = fields[3],
                rawProviderTransactionId = fields[4],
            )
        }
    }

    companion object {
        private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
        private const val EXPECTED_FIELD_COUNT = 5
    }
}
