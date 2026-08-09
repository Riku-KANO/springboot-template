package com.example.template.adapter.messaging

import com.example.template.adapter.messaging.s3.SettlementS3PropertiesConfig
import com.example.template.adapter.messaging.sqs.SettlementSqsPropertiesConfig
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner

class ConfigurationPropertiesValidationTest {
    @Test
    fun `blank settlement bucket fails during context startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(SettlementS3PropertiesConfig::class.java)
            .withPropertyValues("settlement.s3.bucket-name=")
            .run { context ->
                assertThat(context.startupFailure)
                    .isNotNull
                    .hasStackTraceContaining("bucketName")
                    .hasStackTraceContaining("BindValidationException")
            }
    }

    @Test
    fun `blank settlement queue fails during context startup`() {
        ApplicationContextRunner()
            .withUserConfiguration(SettlementSqsPropertiesConfig::class.java)
            .withPropertyValues("settlement.sqs.queue-name=")
            .run { context ->
                assertThat(context.startupFailure)
                    .isNotNull
                    .hasStackTraceContaining("queueName")
                    .hasStackTraceContaining("BindValidationException")
            }
    }
}
