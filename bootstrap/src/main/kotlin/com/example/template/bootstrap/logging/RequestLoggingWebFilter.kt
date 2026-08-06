package com.example.template.bootstrap.logging

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange

/**
 * すべてのリクエストの開始と終了を1行ずつログに残すだけの、composition root 側の最小限の WebFilter。
 *
 * ##### なぜこれが必要か
 * adapter-web の `RequestIdWebFilter` (`@Order(Ordered.HIGHEST_PRECEDENCE)`) は
 * リクエスト ID を発番して `MDCContext` 経由で以降の coroutine 実行に伝播させるが、
 * adapter-web 自体は「その MDC を実際に使ってログを1行出す」コードを (意図的に、
 * GlobalExceptionHandler.kt のコメントが述べる「モデル化された失敗は例外にしない」という
 * 設計のとおり) ほとんど持たない。そのため素の状態では、リクエスト ID が本当に
 * アプリケーションログへ伝播しているかどうかを外から確認する手立てがない。
 *
 * この Filter は、composition root が「requestId が実際にログへ流れ込んでいること」を
 * 実演するために追加した最小限の観測点であり、adapter-web を書き換えずに済む
 * (adapter-web/src/main/.../filter/RequestIdWebFilter.kt は別チャンクの守備範囲であり、
 * ここでは意図的に触れない)。`@Order(Ordered.HIGHEST_PRECEDENCE + 1)` により
 * `RequestIdWebFilter` の直後に実行されるため、ここで `logger.info` する時点では既に
 * MDC に `requestId` が積まれている。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class RequestLoggingWebFilter : CoWebFilter() {
    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        // withContext(MDCContext()) は明示的には不要 (RequestIdWebFilter が既に確立した
        // MDCContext がこの coroutine の実行にそのまま引き継がれているため) だが、
        // 「この Filter 単体で見ても MDC 依存の挙動が自己完結している」ことを示すために
        // 明示的に一度巻き直しておく (RequestIdWebFilter.kt の KDoc の説明と同じ理屈)。
        withContext(MDCContext()) {
            val method = exchange.request.method
            val path = exchange.request.path.value()
            logger.info("handling request: {} {}", method, path)
            chain.filter(exchange)
            logger.info("completed request: {} {} -> {}", method, path, exchange.response.statusCode)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(RequestLoggingWebFilter::class.java)
    }
}
