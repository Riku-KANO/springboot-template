package com.example.template.bootstrap.config

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

/** Flywayの起動時マイグレーション完了後、ECSワンショットタスクを正常終了させる。 */
@Component
@Profile("migration")
class MigrationExitRunner(
    private val context: ConfigurableApplicationContext,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        logger.info("database migration completed successfully")
        exitProcess(SpringApplication.exit(context))
    }

    companion object {
        private val logger = LoggerFactory.getLogger(MigrationExitRunner::class.java)
    }
}
