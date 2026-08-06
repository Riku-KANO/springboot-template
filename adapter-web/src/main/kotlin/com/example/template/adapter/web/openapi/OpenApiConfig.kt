package com.example.template.adapter.web.openapi

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val BEARER_SECURITY_SCHEME = "bearer-jwt"

/**
 * springdoc-openapi の設定。API 情報と JWT ベアラートークン用のセキュリティスキームを
 * 登録する。これにより Swagger UI に "Authorize" ボタンが現れ、JWT を貼り付けて
 * 認証付きのリクエストをブラウザから試せるようになる (SecurityConfig.kt が要求する
 * 認可と対になる設定)。
 */
@Configuration
class OpenApiConfig {
    @Bean
    fun openApi(): OpenAPI =
        OpenAPI()
            .info(
                Info()
                    .title("springboot-template API")
                    .description("Spring Boot + Kotlin + Arrow 関数型テンプレートのサンプル API")
                    .version("v1"),
            ).addSecurityItem(SecurityRequirement().addList(BEARER_SECURITY_SCHEME))
            .components(
                Components()
                    .addSecuritySchemes(
                        BEARER_SECURITY_SCHEME,
                        SecurityScheme()
                            .type(SecurityScheme.Type.HTTP)
                            .scheme("bearer")
                            .bearerFormat("JWT"),
                    ),
            )
}
