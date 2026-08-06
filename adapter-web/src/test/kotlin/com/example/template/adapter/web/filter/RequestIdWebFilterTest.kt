package com.example.template.adapter.web.filter

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import kotlinx.coroutines.delay
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webflux.test.autoconfigure.WebFluxTest
import org.springframework.context.annotation.Import
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * [RequestIdWebFilter] が実演する「suspend + MDCContext による MDC 伝搬」を、
 * 実際の WebFlux ディスパッチ経路 (フィルタ -> suspend コントローラハンドラ) を通して検証する。
 *
 * suspend から Reactor の Mono への変換・逆変換は CoWebFilter/DispatcherHandler 内部の
 * 協調動作に依存しており、テスト側で `CoWebFilterChain` や `WebFilterChain` を素朴に
 * 自作してシミュレートすると、本番の Spring が行っている coroutine コンテキストの
 * 橋渡し (Reactor Context 経由の再構築) を正しく再現できず、偽陰性/偽陽性の原因になる。
 * そのため、ここでは `@WebFluxTest` + 実際に suspend する [ProbeController] を使い、
 * 本番と同じ経路で検証する。
 */
@WebFluxTest(controllers = [ProbeController::class])
@Import(RequestIdWebFilter::class)
class RequestIdWebFilterTest {
    @Autowired
    lateinit var webTestClient: WebTestClient

    private val probeLogger = LoggerFactory.getLogger(ProbeController::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeEach
    fun setUp() {
        appender.start()
        probeLogger.addAppender(appender)
    }

    @AfterEach
    fun tearDown() {
        probeLogger.detachAppender(appender)
    }

    @Test
    fun `requestId set by the filter survives a real suspension point inside the controller`() {
        webTestClient
            .get()
            .uri("/probe")
            .exchange()
            .expectStatus()
            .isOk

        val event = appender.list.singleOrNull { it.formattedMessage.startsWith("inside suspended handler") }
        assertNotNull(event, "suspend ハンドラ内部からのログが見つからない")
        val requestIdInLog = event?.mdcPropertyMap?.get(REQUEST_ID_MDC_KEY)
        assertNotNull(requestIdInLog, "suspend したコルーチンの内部から呼んだログに requestId が MDC に無い")
    }

    @Test
    fun `requestId is echoed back on the response header`() {
        val result =
            webTestClient
                .get()
                .uri("/probe")
                .exchange()
                .expectStatus()
                .isOk
                .returnResult(String::class.java)

        val echoed = result.responseHeaders.getFirst(REQUEST_ID_HEADER)
        assertNotNull(echoed, "レスポンスに $REQUEST_ID_HEADER ヘッダーが無い")

        val event = appender.list.singleOrNull { it.formattedMessage.startsWith("inside suspended handler") }
        val requestIdInLog = event?.mdcPropertyMap?.get(REQUEST_ID_MDC_KEY)
        assertEquals(requestIdInLog, echoed, "レスポンスヘッダーの requestId とログの MDC が一致しない")
    }

    @Test
    fun `an incoming X-Request-Id header is reused instead of generating a new one`() {
        val given = "client-supplied-request-id"

        webTestClient
            .get()
            .uri("/probe")
            .header(REQUEST_ID_HEADER, given)
            .exchange()
            .expectStatus()
            .isOk
            .expectHeader()
            .valueEquals(REQUEST_ID_HEADER, given)

        val event = appender.list.singleOrNull { it.formattedMessage.startsWith("inside suspended handler") }
        assertEquals(given, event?.mdcPropertyMap?.get(REQUEST_ID_MDC_KEY))
    }
}

/**
 * テスト専用の suspend コントローラ。`@WebFluxTest(controllers = [...])` に渡す都合上
 * トップレベルクラスにしている ([RequestIdWebFilterTest] のネストクラスにすると
 * コンポーネントスキャンに乗らず 404 になることを実装時に確認した)。
 */
@RestController
class ProbeController {
    private val logger = LoggerFactory.getLogger(ProbeController::class.java)

    /**
     * `delay` は本物の suspend point。Netty のイベントループ上では、この delay からの
     * 再開が (delay 前とは) 別スレッドで行われる可能性が高い。それでも直後の
     * `logger.info` の MDC に requestId が乗っていることが、このテストの主張である。
     */
    @GetMapping("/probe")
    suspend fun probe(): String {
        delay(20)
        logger.info("inside suspended handler, thread={}", Thread.currentThread().name)
        return "ok"
    }
}
