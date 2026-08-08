package com.example.template.adapter.web.problem

import arrow.core.nonEmptyListOf
import com.example.template.domain.error.DomainError
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.ValueError
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.OrderId
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.context.support.ResourceBundleMessageSource
import org.springframework.http.HttpStatus
import java.util.Locale

class DomainErrorProblemMapperTest {
    private val mapper =
        DomainErrorProblemMapper(
            ResourceBundleMessageSource().apply {
                setBasename("messages")
                setDefaultEncoding("UTF-8")
                setFallbackToSystemLocale(false)
            },
        )

    @Test
    fun `ValidationError category maps to localized 400 response`() {
        val problem = mapper.toProblemDetail(ValueError.BlankOrderId, Locale.ENGLISH)

        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.status)
        assertEquals("ORDER_ID_BLANK", problem.properties?.get("code"))
        assertEquals("Order ID must not be blank.", problem.detail)
    }

    @Test
    fun `NotFoundError category maps to 404`() {
        val error = OrderError.OrderNotFound(OrderId.create("order-1").shouldBeRight())
        val problem = mapper.toProblemDetail(error, Locale.ENGLISH)

        assertEquals(HttpStatus.NOT_FOUND.value(), problem.status)
        assertEquals("ORDER_NOT_FOUND", problem.properties?.get("code"))
    }

    @Test
    fun `ConflictError category maps to 409`() {
        val problem = mapper.toProblemDetail(OrderError.InvalidTransition(OrderStatus.Draft, "ship"), Locale.ENGLISH)

        assertEquals(HttpStatus.CONFLICT.value(), problem.status)
    }

    @Test
    fun `Japanese locale localizes detail title status and accumulated errors`() {
        val problem =
            mapper.toProblemDetail(
                nonEmptyListOf<DomainError>(
                    ValueError.BlankOrderId,
                    ValueError.BlankCustomerId,
                ),
                Locale.JAPANESE,
            )

        assertEquals("リクエストが不正です", problem.title)
        assertEquals("注文IDを入力してください。", problem.detail)
        assertEquals(
            listOf("注文IDを入力してください。", "顧客IDを入力してください。"),
            problem.errors?.map { it.message },
        )
    }

    @Test
    fun `infrastructure diagnostics are not exposed in public response`() {
        val diagnostic = "connection refused at db.internal.example:5432"
        val problem = mapper.toProblemDetail(OrderError.RepositoryUnavailable(diagnostic), Locale.ENGLISH)

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), problem.status)
        assertEquals("ORDER_REPOSITORY_UNAVAILABLE", problem.properties?.get("code"))
        assertFalse(problem.detail.orEmpty().contains(diagnostic))
        assertEquals("The order service is temporarily unavailable. Please try again later.", problem.detail)
    }

    @Test
    fun `different ADT variants receive distinct stable type URIs`() {
        val notFoundType =
            mapper
                .toProblemDetail(OrderError.OrderNotFound(OrderId.create("o1").shouldBeRight()), Locale.ENGLISH)
                .type
        val conflictType =
            mapper
                .toProblemDetail(OrderError.InvalidTransition(OrderStatus.Draft, "ship"), Locale.ENGLISH)
                .type

        assertNotNull(notFoundType)
        assertNotNull(conflictType)
        assertEquals("https://errors.example.com/problems/order-not-found", notFoundType.toString())
        assertEquals("https://errors.example.com/problems/order-invalid-transition", conflictType.toString())
    }

    @Test
    fun `requestId falls back to unknown outside of a filter-established MDC`() {
        val problem = mapper.toProblemDetail(ValueError.BlankOrderId, Locale.ENGLISH)

        assertEquals("unknown", problem.properties?.get("requestId"))
    }
}
