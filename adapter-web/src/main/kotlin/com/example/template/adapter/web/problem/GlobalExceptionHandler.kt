package com.example.template.adapter.web.problem

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.ErrorResponseException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/**
 * 意図的に最小限にとどめた例外ハンドラ。
 *
 * このアプリケーションの「モデル化された失敗」(OrderError, ValidationError 等) は
 * 例外として投げられることがなく、Either の Left 値として各ユースケース・コントローラを
 * 流れ、[DomainErrorProblemMapper] が境界で明示的に ProblemDetail へ変換する。
 * つまり、ここに到達する例外は原理上「Either の世界の外側」で発生したもの
 * (ライブラリのバグ、OutOfMemoryError の一歩手前、想定していない NPE 等)
 * だけであり、本当の意味で「モデル化されていない」ものに限られる。
 *
 * このクラスが小さいこと自体が設計の一部である。個々の例外型ごとにハンドラを
 * 追加したくなったら、まず「その失敗は最初から Either (と DomainError の新しい
 * variant) で表現すべきだったのではないか」を疑うべきだ、というのがこのクラスの
 * サイズが伝えたい教訓。ここに handler を増やすことはこのテンプレートの設計原則から
 * 外れていく兆候であり、安易に増やすべきではない。
 */
@RestControllerAdvice
class GlobalExceptionHandler {
    /**
     * [ErrorResponseException] (その部分型である [org.springframework.web.server.ResponseStatusException]
     * を含む) は Spring 自身が「どのステータスで応答すべきか」を既に把握している、
     * 言わば "Spring にとってのモデル化済みの失敗" (例: マッチするルートが無い場合の 404)。
     * これを下の handleUnexpected で 500 に握りつぶしてしまうと、"URL を打ち間違えただけ"
     * のリクエストまで 500 にしてしまう事故になるため、既に持っている ProblemDetail を
     * そのまま活かして requestId だけ追加する。
     */
    @ExceptionHandler(ErrorResponseException::class)
    fun handleErrorResponse(ex: ErrorResponseException): ProblemDetail {
        val problem = ex.body
        problem.setProperty("requestId", currentRequestId())
        return problem
    }

    @ExceptionHandler(Exception::class)
    fun handleUnexpected(ex: Exception): ProblemDetail {
        val problem = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected error occurred")
        problem.title = HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase
        problem.setProperty("requestId", currentRequestId())
        return problem
    }
}
