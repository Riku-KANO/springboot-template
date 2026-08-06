package com.example.template.adapter.messaging.s3

import com.example.template.domain.error.SettlementError
import com.example.template.domain.testfixtures.shouldBeLeftOfType
import com.example.template.domain.testfixtures.shouldBeRight
import io.awspring.cloud.autoconfigure.core.AwsAutoConfiguration
import io.awspring.cloud.autoconfigure.core.CredentialsProviderAutoConfiguration
import io.awspring.cloud.autoconfigure.core.RegionProviderAutoConfiguration
import io.awspring.cloud.autoconfigure.s3.S3AutoConfiguration
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.localstack.LocalStackContainer
import org.testcontainers.utility.DockerImageName
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import java.time.LocalDate

/**
 * S3SettlementFileAdapter を LocalStack 上の実際の S3 に対して検証する。
 *
 * Spring Cloud AWS には Step Functions 用の starter が存在しないため SfnTaskCallbackAdapter は
 * SDK クライアントを手組みするしかないが (SfnTaskCallbackAdapterIntegrationTest 参照)、
 * S3 は spring-cloud-aws-starter-s3 の自動構成 (S3AutoConfiguration 等) がそのまま使えるため、
 * ここでは本番と同じ自動構成クラス経由で S3Template / S3SettlementFileAdapter を組み立てる
 * (プロパティ駆動のバケット/キー接頭辞を含め、実際の Bean 解決経路をそのまま検証できる)。
 */
@Testcontainers
@SpringBootTest(
    classes = [
        AwsAutoConfiguration::class,
        CredentialsProviderAutoConfiguration::class,
        RegionProviderAutoConfiguration::class,
        S3AutoConfiguration::class,
        S3SettlementFileAdapter::class,
        SettlementS3PropertiesConfig::class,
    ],
)
class S3SettlementFileAdapterIntegrationTest {
    @Autowired
    private lateinit var adapter: S3SettlementFileAdapter

    @Autowired
    private lateinit var s3Client: S3Client

    @BeforeEach
    fun ensureBucketExists() {
        if (!s3Client.listBuckets().buckets().any { it.name() == BUCKET }) {
            s3Client.createBucket { it.bucket(BUCKET) }
        }
    }

    @Test
    fun `S3上の消込ファイルを読み込みパースできた行だけをレコードとして返す`() =
        runTest {
            val date = LocalDate.of(2026, 8, 5)
            val body =
                listOf(
                    "order-1,1000,JPY,2026-08-05T00:00:00Z,txn-1",
                    "this-line-is-malformed",
                    "order-2,2000,JPY,2026-08-05T01:00:00Z,txn-2",
                ).joinToString("\n")
            s3Client.putObject({ it.bucket(BUCKET).key("settlements/2026-08-05.csv") }, RequestBody.fromString(body))

            val records = adapter.readRecordsFor(date).shouldBeRight()

            assertEquals(2, records.size)
            assertEquals("order-1", records[0].orderId.value)
            assertEquals("txn-1", records[0].providerTransactionId)
            assertEquals("order-2", records[1].orderId.value)
        }

    @Test
    fun `消込ファイルが存在しない場合は InfrastructureFailure を返す`() =
        runTest {
            val error = adapter.readRecordsFor(LocalDate.of(1999, 1, 1)).shouldBeLeftOfType<SettlementError.InfrastructureFailure>()

            assertEquals(true, error.cause.contains("not found"))
        }

    companion object {
        private const val BUCKET = "settlement-files-test"

        @Container
        @JvmStatic
        private val localstack: LocalStackContainer =
            LocalStackContainer(DockerImageName.parse("localstack/localstack:4.9")).withServices("s3")

        @DynamicPropertySource
        @JvmStatic
        fun overrideProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.cloud.aws.region.static") { localstack.region }
            registry.add("spring.cloud.aws.credentials.access-key") { localstack.accessKey }
            registry.add("spring.cloud.aws.credentials.secret-key") { localstack.secretKey }
            registry.add("spring.cloud.aws.s3.endpoint") { localstack.endpoint.toString() }
            registry.add("spring.cloud.aws.s3.path-style-access-enabled") { "true" }
            registry.add("settlement.s3.bucket-name") { BUCKET }
        }
    }
}
