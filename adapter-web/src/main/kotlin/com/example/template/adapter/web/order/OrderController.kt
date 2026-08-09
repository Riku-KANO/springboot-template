package com.example.template.adapter.web.order

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import arrow.core.raise.either
import arrow.core.toNonEmptyListOrNull
import com.example.template.adapter.web.order.dto.CancelOrderRequest
import com.example.template.adapter.web.order.dto.CreateOrderRequest
import com.example.template.adapter.web.order.dto.OrderPageResponse
import com.example.template.adapter.web.order.dto.RefundOrderRequest
import com.example.template.adapter.web.order.dto.ShipOrderRequest
import com.example.template.adapter.web.problem.DomainErrorProblemMapper
import com.example.template.adapter.web.problem.apiLocale
import com.example.template.application.order.CancelOrder
import com.example.template.application.order.CancelOrderCommand
import com.example.template.application.order.CreateOrder
import com.example.template.application.order.CreateOrderCommand
import com.example.template.application.order.DeliverOrder
import com.example.template.application.order.DeliverOrderCommand
import com.example.template.application.order.FindOrder
import com.example.template.application.order.FindOrderQuery
import com.example.template.application.order.ListOrders
import com.example.template.application.order.ListOrdersQuery
import com.example.template.application.order.PayOrder
import com.example.template.application.order.PayOrderCommand
import com.example.template.application.order.RefundOrder
import com.example.template.application.order.RefundOrderCommand
import com.example.template.application.order.ShipOrder
import com.example.template.application.order.ShipOrderCommand
import com.example.template.application.order.StartFulfillment
import com.example.template.application.order.StartFulfillmentCommand
import com.example.template.application.order.SubmitOrderForPayment
import com.example.template.application.order.SubmitOrderForPaymentCommand
import com.example.template.domain.error.DomainError
import com.example.template.domain.order.Order
import com.example.template.domain.shared.Money
import com.example.template.domain.shared.MoneyMinor
import com.example.template.domain.shared.OrderId
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import java.util.Currency

/**
 * 注文リソースの Web アダプタ。
 *
 * 全ハンドラが suspend 関数である点に注目してほしい。WebFlux は Kotlin coroutine を
 * ネイティブにサポートしており、suspend なハンドラメソッドはフレームワーク側が
 * 自動的に Mono に変換してくれる。これにより Arrow の `either { }` DSL を
 * 命令的なコードに近い書き味のまま suspend コンテキストの中で使える
 * (Reactor の Mono/Flux 演算子チェーンの中で Either を持ち回るよりずっと素直に書ける) ---
 * これが本テンプレートが「WebFlux + coroutine + Arrow」という組み合わせを選んだ理由そのもの。
 *
 * 各ハンドラの型は `ResponseEntity<*>` で統一している。成功時は DTO を、失敗時は
 * [ProblemDetail] を返すため、Kotlin の型システムでは静的に単一の型を書けない
 * (両者に共通の親は Any 程度しかない) ためのワイルドカードである。
 */
@RestController
@RequestMapping("/orders")
class OrderController(
    private val createOrder: CreateOrder,
    private val findOrder: FindOrder,
    private val listOrders: ListOrders,
    private val submitOrderForPayment: SubmitOrderForPayment,
    private val payOrder: PayOrder,
    private val startFulfillment: StartFulfillment,
    private val shipOrder: ShipOrder,
    private val deliverOrder: DeliverOrder,
    private val cancelOrder: CancelOrder,
    private val refundOrder: RefundOrder,
    private val problemMapper: DomainErrorProblemMapper,
) {
    @GetMapping
    suspend fun list(
        @RequestParam(required = false) after: String?,
        @RequestParam(defaultValue = "20") limit: Int,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        if (limit !in 1..100) {
            return problemMapper.badRequest("PAGE_LIMIT_INVALID", "error.request.page-limit.invalid", exchange.apiLocale())
        }
        return either {
            val cursor = after?.let { OrderId.create(it).bind() }
            listOrders(ListOrdersQuery(cursor, limit)).bind()
        }.fold(
            { problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) },
            { page -> ResponseEntity.ok(OrderPageResponse(page.items.map { it.toResponse() }, page.nextCursor?.value)) },
        )
    }

    /**
     * 注文作成。ヘッドライン機能である「累積バリデーション」をここで実演する。
     * orderId・customerId・明細の3軸すべてが不正な場合、[CreateOrderCommand.create] の
     * `EitherNel<ValidationError, _>` によって3件のエラーがまとめて1回のレスポンスで
     * 返る (詳細は problem/DomainErrorProblemMapper.kt を参照)。
     */
    @PostMapping
    suspend fun create(
        @RequestBody request: CreateOrderRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        val currency =
            runCatching { Currency.getInstance(request.currency) }.getOrNull()
                ?: return problemMapper.badRequest(
                    "CURRENCY_CODE_INVALID",
                    "error.request.currency.invalid",
                    exchange.apiLocale(),
                    request.currency,
                )
        val rawLines =
            request.lines.map { it.toRaw() }.toNonEmptyListOrNull()
                ?: return problemMapper.badRequest("ORDER_LINES_EMPTY", "error.request.order-lines.empty", exchange.apiLocale())

        // Either<NonEmptyList<ValidationError>, _>.bind() と Either<OrderError, _>.bind() を
        // 同じ either ブロックの中で混在させられるのは、arrow.core.raise.Raise が
        // Error 型引数について反変 (in) だから: Raise<NonEmptyList<DomainError>> は
        // Raise<NonEmptyList<ValidationError>> としても Raise<NonEmptyList<OrderError>>
        // としても使える (ValidationError / OrderError はどちらも DomainError の部分型で、
        // NonEmptyList<out E> は共変なので NonEmptyList<ValidationError> <: NonEmptyList<DomainError>)。
        // createOrder が返す単一の OrderError は nonEmptyListOf(...) で NEL に包んでから
        // bind することで、両方の失敗を同じ NonEmptyList<DomainError> という Left 型に揃えている。
        return either<NonEmptyList<DomainError>, Order> {
            val command =
                CreateOrderCommand
                    .create(request.orderId, request.customerId, rawLines, currency, request.address.toDomain())
                    .bind()
            createOrder(command).mapLeft { nonEmptyListOf(it) }.bind()
        }.fold(
            { problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) },
            { ResponseEntity.status(HttpStatus.CREATED).body(it.toResponse()) },
        )
    }

    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> =
        either {
            val orderId = OrderId.create(id).bind()
            findOrder(FindOrderQuery(orderId)).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })

    /**
     * 注文を確定し決済待ちにする (Draft -> PendingPayment)。
     * これと `start-fulfillment` を追加するまでは、新規作成した注文は Draft のまま止まり、
     * `pay` (PendingPayment 前提) にも `ship` (Fulfilling 前提) にも決して到達できなかった
     * (ライフサイクルの断絶については OrderLifecycleTest のコメントを参照)。
     */
    @PostMapping("/{id}/submit")
    suspend fun submit(
        @PathVariable id: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> =
        either {
            val orderId = OrderId.create(id).bind()
            submitOrderForPayment(SubmitOrderForPaymentCommand(orderId)).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })

    @PostMapping("/{id}/pay")
    suspend fun pay(
        @PathVariable id: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> =
        either {
            val orderId = OrderId.create(id).bind()
            payOrder(PayOrderCommand(orderId)).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })

    /**
     * 倉庫でのピッキング・梱包着手を記録する (Paid -> Fulfilling)。`ship` の前提状態を作る。
     * ハンドラ名を注入プロパティ (`startFulfillment: StartFulfillment`) と区別するため、
     * pay/payOrder・ship/shipOrder・cancel/cancelOrder と同じ命名方針
     * (プロパティは名詞のユースケース名、ハンドラは動詞) に倣い domain の遷移関数名
     * (`startFulfilling`) をそのままハンドラ名にする。
     */
    @PostMapping("/{id}/start-fulfillment")
    suspend fun startFulfilling(
        @PathVariable id: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> =
        either {
            val orderId = OrderId.create(id).bind()
            startFulfillment(StartFulfillmentCommand(orderId)).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })

    @PostMapping("/{id}/ship")
    suspend fun ship(
        @PathVariable id: String,
        @RequestBody request: ShipOrderRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> =
        either {
            val orderId = OrderId.create(id).bind()
            shipOrder(ShipOrderCommand(orderId, request.trackingNumber)).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })

    /** 顧客への配達完了を記録する (Shipped -> Delivered)。7つの domain 遷移のうち最後の1つ。 */
    @PostMapping("/{id}/deliver")
    suspend fun deliver(
        @PathVariable id: String,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> =
        either {
            val orderId = OrderId.create(id).bind()
            deliverOrder(DeliverOrderCommand(orderId)).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })

    @PostMapping("/{id}/cancel")
    suspend fun cancel(
        @PathVariable id: String,
        @RequestBody request: CancelOrderRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> =
        either {
            val orderId = OrderId.create(id).bind()
            cancelOrder(CancelOrderCommand(orderId, request.reason)).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })

    /**
     * 注文を返金済みにする ({Paid, Delivered} -> Refunded)。返金額は生の Long/String のまま
     * :application に渡さず、ここ (Web 境界) で Currency.getInstance / MoneyMinor.create という
     * 既存のスマートコンストラクタを通してから Money として渡す。通貨コードの妥当性検証を
     * create() と同じ「構造的に壊れたリクエストをフェイルファストで弾く」箇所に置くのも create() と
     * 同じ理由 (Currency は :domain にスマートコンストラクタを持たない JDK の型のため)。
     */
    @PostMapping("/{id}/refund")
    suspend fun refund(
        @PathVariable id: String,
        @RequestBody request: RefundOrderRequest,
        exchange: ServerWebExchange,
    ): ResponseEntity<*> {
        val currency =
            runCatching { Currency.getInstance(request.currency) }.getOrNull()
                ?: return problemMapper.badRequest(
                    "CURRENCY_CODE_INVALID",
                    "error.request.currency.invalid",
                    exchange.apiLocale(),
                    request.currency,
                )
        return either {
            val orderId = OrderId.create(id).bind()
            val amountMinor = MoneyMinor.create(request.amountMinor).bind()
            refundOrder(RefundOrderCommand(orderId, Money(amountMinor, currency))).bind()
        }.fold({ problemMapper.toProblemDetailResponse(it, exchange.apiLocale()) }, { ResponseEntity.ok(it.toResponse()) })
    }

    /**
     * 通貨コードの妥当性と「明細が1件以上あるか」は、累積バリデーション
     * ([CreateOrderCommand.create]) には乗せられない。前者は :domain にスマート
     * コンストラクタが存在しない JDK の型 (java.util.Currency) の妥当性であり、
     * 後者はそもそも `NonEmptyList` へ変換できるかどうかという「型を組み立てる前提条件」
     * だからだ。DomainError の新しい variant を :adapter-web 側で追加することもできない
     * (sealed interface は同一モジュール内でしか実装できない — domain/error/OrderError.kt の
     * コメント参照) ため、これら2つはドメインのビジネスルール違反ではなく
     * 「リクエストの形そのものが壊れている」構造的エラーとして、累積バリデーションの
     * パイプラインに入る前にここでフェイルファストに弾く。3件同時に返る累積バリデーションの
     * デモは、この前処理を通過した後の orderId・customerId・明細内容の3軸に対して行う。
     */
}
