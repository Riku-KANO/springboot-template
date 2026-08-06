package com.example.template.adapter.messaging.sfn

import arrow.core.Either
import com.example.template.application.port.TaskCallbackError
import com.example.template.application.port.TaskCallbackPort
import com.example.template.application.port.TaskToken
import com.example.template.domain.error.DomainError
import kotlinx.coroutines.future.await
import org.springframework.stereotype.Component
import software.amazon.awssdk.services.sfn.SfnAsyncClient
import tools.jackson.databind.ObjectMapper

/**
 * [TaskCallbackPort] の AWS 実装。Step Functions の `.waitForTaskToken` パターンに対する
 * `SendTaskSuccess` / `SendTaskFailure` を [SfnAsyncClient] で呼び出す。
 *
 * SfnAsyncClient が返すのは `CompletableFuture` (SDK 内部のノンブロッキング HTTP クライアント上に
 * 構築されている) であり、これを coroutine の `suspend` にそのまま橋渡しするために
 * kotlinx-coroutines-jdk8 の `.await()` を使う。ここで `.join()` や `.get()` のような
 * ブロッキング API を使ってしまうと、呼び出し元が WebFlux のイベントループスレッド上で
 * 実行されていた場合にスレッドを占有してしまい、リアクティブスタック全体のスループットを
 * 損なう。suspend 関数として最後まで非同期を維持するのがこのアダプタの責務。
 *
 * SDK が投げうる例外 (SdkClientException, SfnException 等) は `Either.catch` でその場に閉じ込め、
 * [TaskCallbackError] に変換する。TaskCallbackPort の呼び出し元 (:batch の
 * SettlementJobListener 等) は AWS SDK の存在を一切知らずに済む。
 */
@Component
class SfnTaskCallbackAdapter(
    private val sfnAsyncClient: SfnAsyncClient,
    private val objectMapper: ObjectMapper,
) : TaskCallbackPort {
    override suspend fun <A> notifySuccess(
        taskToken: TaskToken,
        payload: A,
    ): Either<TaskCallbackError, Unit> =
        Either
            .catch {
                val outputJson = objectMapper.writeValueAsString(payload)
                sfnAsyncClient
                    .sendTaskSuccess { builder -> builder.taskToken(taskToken.value).output(outputJson) }
                    .await()
                Unit
            }.mapLeft { throwable -> TaskCallbackError(throwable.message ?: "sendTaskSuccess failed unexpectedly") }

    override suspend fun notifyFailure(
        taskToken: TaskToken,
        error: DomainError,
    ): Either<TaskCallbackError, Unit> =
        Either
            .catch {
                sfnAsyncClient
                    .sendTaskFailure { builder ->
                        builder
                            .taskToken(taskToken.value)
                            // Step Functions の Catch/Retry は Error フィールドの文字列一致で分岐するため、
                            // DomainError の具象クラス名 (例: "SettlementError.UnknownOrder") をそのまま使う。
                            .error(error::class.simpleName ?: "DomainError")
                            .cause(error.message)
                    }.await()
                Unit
            }.mapLeft { throwable -> TaskCallbackError(throwable.message ?: "sendTaskFailure failed unexpectedly") }
}
