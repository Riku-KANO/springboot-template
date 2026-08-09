package com.example.template.bootstrap

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient
import org.springframework.http.MediaType
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "template.security.permit-all=true",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://example.invalid/jwks.json",
        "spring.flyway.enabled=true",
        "spring.batch.job.enabled=false",
        "spring.cloud.aws.sqs.listener.auto-startup=false",
        "spring.cloud.aws.region.static=us-east-1",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "spring.cloud.aws.endpoint=http://localhost:1",
        "settlement.s3.bucket-name=integration-test",
        "settlement.sqs.queue-name=integration-test",
    ],
)
@ContextConfiguration(initializers = [TemplateBeansInitializer::class])
@AutoConfigureWebTestClient
class TemplateApplicationIntegrationTest {
    @Autowired
    lateinit var webTestClient: WebTestClient

    @Test
    fun `composition root starts and serves a database backed request`() {
        webTestClient
            .get()
            .uri("/actuator/health/readiness")
            .exchange()
            .expectStatus()
            .isOk

        webTestClient
            .post()
            .uri("/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """
                {
                  "orderId":"bootstrap-test-1",
                  "customerId":"customer-1",
                  "currency":"JPY",
                  "lines":[{"sku":"SKU-1","quantity":1,"unitPriceMinor":1000}],
                  "address":{
                    "recipientName":"Taro Yamada",
                    "postalCode":"100-0001",
                    "prefecture":"Tokyo",
                    "city":"Chiyoda",
                    "addressLine1":"1-1"
                  }
                }
                """.trimIndent(),
            ).exchange()
            .expectStatus()
            .isCreated

        webTestClient
            .get()
            .uri("/orders/bootstrap-test-1")
            .exchange()
            .expectStatus()
            .isOk
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine")).also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.r2dbc.url") {
                "r2dbc:postgresql://${postgres.host}:${postgres.getMappedPort(5432)}/${postgres.databaseName}"
            }
            registry.add("spring.r2dbc.username") { postgres.username }
            registry.add("spring.r2dbc.password") { postgres.password }
        }
    }
}
