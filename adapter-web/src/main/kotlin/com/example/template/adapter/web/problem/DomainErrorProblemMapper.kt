package com.example.template.adapter.web.problem

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import com.example.template.adapter.web.filter.REQUEST_ID_MDC_KEY
import com.example.template.domain.error.ConflictError
import com.example.template.domain.error.DomainError
import com.example.template.domain.error.InfrastructureError
import com.example.template.domain.error.NotFoundError
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.SettlementError
import com.example.template.domain.error.ValidationError
import com.example.template.domain.error.ValueError
import com.example.template.domain.order.OrderStatus
import org.slf4j.MDC
import org.springframework.context.MessageSource
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import java.net.URI
import java.util.Locale

private const val PROBLEM_BASE_URI = "https://errors.example.com/problems"
private const val REQUEST_ID_PROPERTY = "requestId"
private const val CODE_PROPERTY = "code"
private const val ERRORS_PROPERTY = "errors"

/** API利用者が機械的に判定できる安定コードと、人間向けのローカライズ済み文言。 */
data class ApiError(
    val code: String,
    val message: String,
)

private data class ErrorDescriptor(
    val code: String,
    val messageKey: String,
    val arguments: Array<out Any> = emptyArray(),
)

/**
 * ドメインエラーをHTTP表現へ変換する境界。
 *
 * ドメイン層の [DomainError.message] はログ・バッチ向けの診断文として維持し、公開APIには直接出さない。
 * APIではこのクラスが安定したエラーコードと、Accept-Languageに応じた表示文言へ変換する。
 */
@Component
class DomainErrorProblemMapper(
    private val messageSource: MessageSource,
) {
    fun toProblemDetail(
        errors: NonEmptyList<DomainError>,
        locale: Locale,
    ): ProblemDetail {
        val localizedErrors = errors.map { localize(it, locale) }
        val status = errors.head.httpStatus()
        val problem = ProblemDetail.forStatusAndDetail(status, localizedErrors.head.message)
        problem.title = message("problem.title.${status.value()}", locale)
        problem.type = URI.create("$PROBLEM_BASE_URI/${localizedErrors.head.code.lowercase().replace('_', '-')}")
        problem.setProperty(REQUEST_ID_PROPERTY, currentRequestId())
        problem.setProperty(CODE_PROPERTY, localizedErrors.head.code)
        problem.setProperty(ERRORS_PROPERTY, localizedErrors.all)
        return problem
    }

    fun toProblemDetail(
        error: DomainError,
        locale: Locale,
    ): ProblemDetail = toProblemDetail(nonEmptyListOf(error), locale)

    fun toProblemDetailResponse(
        errors: NonEmptyList<DomainError>,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.of(toProblemDetail(errors, locale)).build()

    fun toProblemDetailResponse(
        error: DomainError,
        locale: Locale,
    ): ResponseEntity<ProblemDetail> = ResponseEntity.of(toProblemDetail(error, locale)).build()

    fun badRequest(
        code: String,
        messageKey: String,
        locale: Locale,
        vararg arguments: Any,
    ): ResponseEntity<ProblemDetail> {
        val localized = message(messageKey, locale, *arguments)
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, localized)
        problem.title = message("problem.title.400", locale)
        problem.type = URI.create("$PROBLEM_BASE_URI/${code.lowercase().replace('_', '-')}")
        problem.setProperty(REQUEST_ID_PROPERTY, currentRequestId())
        problem.setProperty(CODE_PROPERTY, code)
        problem.setProperty(ERRORS_PROPERTY, listOf(ApiError(code, localized)))
        return ResponseEntity.of(problem).build()
    }

    fun message(
        key: String,
        locale: Locale,
        vararg arguments: Any,
    ): String = messageSource.getMessage(key, arguments, locale)

    private fun localize(
        error: DomainError,
        locale: Locale,
    ): ApiError {
        val descriptor = error.descriptor(locale)
        return ApiError(
            code = descriptor.code,
            message = message(descriptor.messageKey, locale, *descriptor.arguments),
        )
    }

    private fun DomainError.descriptor(locale: Locale): ErrorDescriptor =
        when (this) {
            is ValueError.BlankOrderId -> ErrorDescriptor("ORDER_ID_BLANK", "error.order-id.blank")
            is ValueError.OrderIdTooLong ->
                ErrorDescriptor("ORDER_ID_TOO_LONG", "error.order-id.too-long", arrayOf(length, ValueError.MAX_ID_LENGTH))
            is ValueError.BlankCustomerId -> ErrorDescriptor("CUSTOMER_ID_BLANK", "error.customer-id.blank")
            is ValueError.CustomerIdTooLong ->
                ErrorDescriptor("CUSTOMER_ID_TOO_LONG", "error.customer-id.too-long", arrayOf(length, ValueError.MAX_ID_LENGTH))
            is ValueError.InvalidSkuFormat -> ErrorDescriptor("SKU_INVALID_FORMAT", "error.sku.invalid-format", arrayOf(raw))
            is ValueError.NonPositiveQuantity -> ErrorDescriptor("QUANTITY_NON_POSITIVE", "error.quantity.non-positive", arrayOf(raw))
            is ValueError.QuantityTooLarge ->
                ErrorDescriptor("QUANTITY_TOO_LARGE", "error.quantity.too-large", arrayOf(raw, ValueError.MAX_QUANTITY))
            is ValueError.NegativeMoneyAmount -> ErrorDescriptor("MONEY_AMOUNT_NEGATIVE", "error.money.negative", arrayOf(raw))
            is ValueError.MoneyAmountOverflow -> ErrorDescriptor("MONEY_AMOUNT_OVERFLOW", "error.money.overflow")
            is ValueError.FractionalMinorUnit -> ErrorDescriptor("MONEY_FRACTIONAL_MINOR_UNIT", "error.money.fractional", arrayOf(raw))
            is ValueError.CurrencyMismatch ->
                ErrorDescriptor("CURRENCY_MISMATCH", "error.money.currency-mismatch", arrayOf(expected.currencyCode, actual.currencyCode))
            is OrderError.InvalidOrderLine -> ErrorDescriptor("ORDER_INVALID_LINE", "error.order.invalid-line", arrayOf(reason))
            is OrderError.OrderNotFound -> ErrorDescriptor("ORDER_NOT_FOUND", "error.order.not-found", arrayOf(orderId.value))
            is OrderError.InvalidTransition ->
                ErrorDescriptor(
                    "ORDER_INVALID_TRANSITION",
                    "error.order.invalid-transition",
                    arrayOf(attempted, message(statusMessageKey(from), locale)),
                )
            is OrderError.PaymentGatewayUnavailable ->
                ErrorDescriptor("PAYMENT_GATEWAY_UNAVAILABLE", "error.payment-gateway.unavailable")
            is OrderError.RepositoryUnavailable -> ErrorDescriptor("ORDER_REPOSITORY_UNAVAILABLE", "error.order-repository.unavailable")
            is SettlementError.MalformedRecord ->
                ErrorDescriptor("SETTLEMENT_RECORD_MALFORMED", "error.settlement.malformed-record", arrayOf(reason))
            is SettlementError.UnknownOrder ->
                ErrorDescriptor("SETTLEMENT_ORDER_UNKNOWN", "error.settlement.unknown-order", arrayOf(orderId.value))
            is SettlementError.InfrastructureFailure ->
                ErrorDescriptor("SETTLEMENT_INFRASTRUCTURE_FAILURE", "error.settlement.infrastructure-failure")
        }
}

private fun statusMessageKey(status: OrderStatus): String =
    when (status) {
        is OrderStatus.Draft -> "order.status.draft"
        is OrderStatus.PendingPayment -> "order.status.pending-payment"
        is OrderStatus.Paid -> "order.status.paid"
        is OrderStatus.Fulfilling -> "order.status.fulfilling"
        is OrderStatus.Shipped -> "order.status.shipped"
        is OrderStatus.Delivered -> "order.status.delivered"
        is OrderStatus.Cancelled -> "order.status.cancelled"
        is OrderStatus.Refunded -> "order.status.refunded"
    }

private fun DomainError.httpStatus(): HttpStatus =
    when (this) {
        is ValidationError -> HttpStatus.BAD_REQUEST
        is NotFoundError -> HttpStatus.NOT_FOUND
        is ConflictError -> HttpStatus.CONFLICT
        is InfrastructureError -> HttpStatus.SERVICE_UNAVAILABLE
    }

/** 対応言語は日本語と英語。未対応言語およびAccept-Language省略時は英語へフォールバックする。 */
fun ServerWebExchange.apiLocale(): Locale =
    runCatching { request.headers.acceptLanguageAsLocales }
        .getOrDefault(emptyList())
        .firstOrNull { it.language == Locale.JAPANESE.language || it.language == Locale.ENGLISH.language }
        ?.let { if (it.language == Locale.JAPANESE.language) Locale.JAPANESE else Locale.ENGLISH }
        ?: Locale.ENGLISH

fun currentRequestId(): String = MDC.get(REQUEST_ID_MDC_KEY) ?: "unknown"

@Suppress("UNCHECKED_CAST")
val ProblemDetail.errors: List<ApiError>?
    get() = properties?.get(ERRORS_PROPERTY) as? List<ApiError>
