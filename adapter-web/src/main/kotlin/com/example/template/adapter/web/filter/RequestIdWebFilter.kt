package com.example.template.adapter.web.filter

import kotlinx.coroutines.slf4j.MDCContext
import kotlinx.coroutines.withContext
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.server.CoWebFilter
import org.springframework.web.server.CoWebFilterChain
import org.springframework.web.server.ServerWebExchange
import java.util.UUID

/** クライアントが相関 ID を指定してくる場合に使うヘッダー名。無ければこちらで発番して応答にも積む。 */
const val REQUEST_ID_HEADER = "X-Request-Id"

/** SLF4J MDC に requestId を積むキー。ログ設定 (logback パターン等) からもこの名前で参照する。 */
const val REQUEST_ID_MDC_KEY = "requestId"

/**
 * リクエスト ID (相関 ID) を発行・伝搬する WebFilter。
 *
 * ##### なぜ素の SLF4J MDC だけでは足りないのか
 * WebFlux は Netty のイベントループ上で動く。1本のリクエスト処理は複数の suspend 関数を
 * またぐが、coroutine は「suspend したら必ず同じスレッドに戻ってくる」ことを一切
 * 保証しない — R2DBC の応答待ちなどで一度 suspend すると、多くの場合 Reactor の
 * スケジューラが別スレッドで処理を再開させる。ところが SLF4J の MDC は生の
 * ThreadLocal であるため、あるスレッドで `MDC.put("requestId", ...)` しても、
 * coroutine が別スレッドで再開した瞬間にその値は見えなくなる (別スレッドの
 * ThreadLocal は空か、最悪の場合そのスレッドが以前処理していた別リクエストの
 * 値が残っている可能性すらある)。
 *
 * ##### 解決その1: CoWebFilter — suspend な WebFilter
 * 通常の `WebFilter#filter` は `Mono<Void>` を返すコールバックスタイルだが、
 * [CoWebFilter] は中身が suspend 関数になっている。ここで `withContext` により
 * 確立した [kotlin.coroutines.CoroutineContext] は、この後 `chain.filter(exchange)`
 * (これも suspend) を呼び出すことで、後続の WebFilter・最終的なハンドラメソッド
 * (order/OrderController.kt の各 suspend 関数) まで、"1つの coroutine の実行" として
 * そのまま伝播する。つまり、ここで積んだコンテキスト要素は、途中で明示的に
 * 引き回すコードを1行も書かなくても、リクエスト処理の最後まで届く。
 *
 * ##### 解決その2: MDCContext — ThreadContextElement
 * [kotlinx.coroutines.slf4j.MDCContext] はただの「値を保持するだけの
 * CoroutineContext.Element」ではなく [kotlinx.coroutines.ThreadContextElement] を
 * 実装している。coroutine が (suspend からの再開などで) あるスレッドで実行を
 * 開始・再開するたびに `updateThreadContext` が呼ばれてそのスレッドの MDC を
 * 復元し、そのスレッドでの実行を終えて再び suspend する際には `restoreThreadContext`
 * で「そのスレッドが本来持っていた MDC」に戻す。つまり MDCContext は
 * 「coroutine がジャンプする先のスレッドすべてに、都度 MDC を運んでくれる」
 * ということであり、CoWebFilter (解決その1) と組み合わせることで、
 * 呼び出しチェーンの途中の関数は requestId の存在を一切意識せずに、普通に
 * `logger.info(...)` するだけでログに requestId が乗る ("zero plumbing")。
 * 逆に MDCContext 抜きで `withContext(Dispatchers.IO)` のようにディスパッチャだけ
 * 切り替えても、切り替え先のスレッドの MDC は空のままなので、この効果は得られない。
 *
 * `@Order(Ordered.HIGHEST_PRECEDENCE)` で最優先に実行させているのは、Spring Security の
 * フィルタチェーンより前段で requestId を確立するため。認証エラーで 401 を返す
 * レスポンスにも、その失敗をログに残す際にも、常に requestId が乗る。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIdWebFilter : CoWebFilter() {
    override suspend fun filter(
        exchange: ServerWebExchange,
        chain: CoWebFilterChain,
    ) {
        val requestId = exchange.request.headers.getFirst(REQUEST_ID_HEADER) ?: UUID.randomUUID().toString()
        exchange.response.headers.set(REQUEST_ID_HEADER, requestId)
        withContext(MDCContext(mapOf(REQUEST_ID_MDC_KEY to requestId))) {
            chain.filter(exchange)
        }
    }
}
