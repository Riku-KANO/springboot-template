package com.example.template.adapter.web.problem

import arrow.core.nonEmptyListOf
import com.example.template.domain.error.DomainError
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.ValueError
import com.example.template.domain.order.OrderStatus
import com.example.template.domain.shared.OrderId
import com.example.template.domain.testfixtures.shouldBeRight
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus

/**
 * DomainError -> ProblemDetail のマッピングを純粋な単体テストとして検証する。
 * Spring コンテキストは不要 (関数を直接呼ぶだけ) なので高速に回る。
 */
class DomainErrorProblemMapperTest {
    @Test
    fun `ValidationError category maps to 400`() {
        val problem = ValueError.BlankOrderId.toProblemDetail()
        assertEquals(HttpStatus.BAD_REQUEST.value(), problem.status)
        assertEquals(ValueError.BlankOrderId.message, problem.detail)
    }

    @Test
    fun `NotFoundError category maps to 404`() {
        val orderId = OrderId.create("order-1").shouldBeRight()
        val problem = OrderError.OrderNotFound(orderId).toProblemDetail()
        assertEquals(HttpStatus.NOT_FOUND.value(), problem.status)
    }

    @Test
    fun `ConflictError category maps to 409`() {
        val problem = OrderError.InvalidTransition(OrderStatus.Draft, "ship").toProblemDetail()
        assertEquals(HttpStatus.CONFLICT.value(), problem.status)
    }

    @Test
    fun `InfrastructureError category maps to 503`() {
        val problem = OrderError.PaymentGatewayUnavailable("timeout").toProblemDetail()
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE.value(), problem.status)
    }

    @Test
    fun `OrderError variants get distinct type slugs (concrete ADT exhaustiveness in action)`() {
        val notFoundType = OrderError.OrderNotFound(OrderId.create("o1").shouldBeRight()).toProblemDetail().type
        val conflictType = OrderError.InvalidTransition(OrderStatus.Draft, "ship").toProblemDetail().type
        assertNotNull(notFoundType)
        assertNotNull(conflictType)
        assertEquals(true, notFoundType.toString().endsWith("order-not-found"))
        assertEquals(true, conflictType.toString().endsWith("order-invalid-transition"))
    }

    @Test
    fun `accumulated errors all appear in the errors property`() {
        val problem =
            nonEmptyListOf<DomainError>(ValueError.BlankOrderId, ValueError.BlankCustomerId)
                .toProblemDetail()

        val errors = problem.errors
        assertNotNull(errors)
        assertEquals(2, errors?.size)
        assertEquals(true, errors?.contains(ValueError.BlankOrderId.message))
        assertEquals(true, errors?.contains(ValueError.BlankCustomerId.message))
    }

    @Test
    fun `requestId falls back to unknown outside of a filter-established MDC`() {
        val problem = ValueError.BlankOrderId.toProblemDetail()
        assertEquals("unknown", problem.properties?.get("requestId"))
    }

    @Test
    fun `problemTypeSlug distinguishes OrderError from plain category errors sharing the same status`() {
        val orderNotFoundType = OrderError.OrderNotFound(OrderId.create("o1").shouldBeRight()).toProblemDetail().type
        // ValueError は adapter-web からは ValidationError というカテゴリマーカーとしてしか
        // 見えず、OrderError のような具象 ADT 単位の詳細スラッグは付与されない。
        val validationErrorType = ValueError.BlankOrderId.toProblemDetail().type
        assertEquals("https://errors.example.com/problems/order-not-found", orderNotFoundType.toString())
        assertEquals("https://errors.example.com/problems/validation-error", validationErrorType.toString())
    }
}
