package com.example.template.adapter.web.problem

import arrow.core.NonEmptyList
import arrow.core.nonEmptyListOf
import com.example.template.adapter.web.filter.REQUEST_ID_MDC_KEY
import com.example.template.domain.error.ConflictError
import com.example.template.domain.error.DomainError
import com.example.template.domain.error.InfrastructureError
import com.example.template.domain.error.NotFoundError
import com.example.template.domain.error.OrderError
import com.example.template.domain.error.ValidationError
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import java.net.URI

private const val PROBLEM_BASE_URI = "https://errors.example.com/problems"

/*
 * DomainError (Either の Left 値) から RFC 9457 ProblemDetail への変換。
 *
 * エラーは例外として投げられるのではなく Either の Left 値として運ばれてくるため、
 * try/catch で暗黙に処理されることはない。この変換をコントローラ境界で明示的に
 * 呼び出すことで、「どんな失敗がどんな HTTP レスポンスになるか」がコードを読むだけで
 * 追える状態を保つ。
 */

/**
 * 累積バリデーション結果 (NonEmptyList<DomainError>) を1つの ProblemDetail に変換する。
 * 単一エラーの [toProblemDetail] もこれに委譲することで実装を1箇所に集約している。
 *
 * 先頭要素のカテゴリを HTTP ステータス・type URI の代表として使う。本テンプレートでは
 * 累積バリデーションは常に単一の検証ステージ (CreateOrderCommand.create) の産物であり、
 * 現実には NEL 内の全要素が同じカテゴリ (ValidationError) になる。将来カテゴリの
 * 混在した NEL を作るユースケースが増えた場合にこの単純化で十分かは改めて検討が要る、
 * という前提を明示するためにここにコメントを残す。
 */
fun NonEmptyList<DomainError>.toProblemDetail(): ProblemDetail {
    val status = head.httpStatus()
    val problem = ProblemDetail.forStatusAndDetail(status, head.message)
    problem.title = status.reasonPhrase
    problem.type = URI.create("$PROBLEM_BASE_URI/${head.problemTypeSlug()}")
    problem.setProperty(REQUEST_ID_PROPERTY, currentRequestId())
    problem.setProperty(ERRORS_PROPERTY, map { it.message })
    return problem
}

fun DomainError.toProblemDetail(): ProblemDetail = nonEmptyListOf(this).toProblemDetail()

fun NonEmptyList<DomainError>.toProblemDetailResponse(): ResponseEntity<ProblemDetail> = ResponseEntity.of(toProblemDetail()).build()

fun DomainError.toProblemDetailResponse(): ResponseEntity<ProblemDetail> = nonEmptyListOf(this).toProblemDetailResponse()

private const val REQUEST_ID_PROPERTY = "requestId"
private const val ERRORS_PROPERTY = "errors"

/**
 * MDC に積まれた requestId (filter/RequestIdWebFilter.kt 参照) を読み出す。
 * suspend コントローラの呼び出しチェーンの中で、この関数はどのスレッドで実行されて
 * いても正しい値を返す。これは呼び出し元が明示的に requestId を引き回しているのではなく、
 * MDCContext がコルーチンの再開ごとに MDC を復元しているからであり、「ログや
 * ProblemDetail 生成のような末端の関心事が、リクエストの相関 ID を一切意識せずに
 * 済む」という MDC 伝搬の価値をそのまま体現している。
 * フィルタを経由しない単体テスト等、MDC が空の場合は "unknown" にフォールバックする。
 */
fun currentRequestId(): String = MDC.get(REQUEST_ID_MDC_KEY) ?: "unknown"

/**
 * ProblemDetail の `errors` プロパティを読み出す拡張。RFC 9457 は `errors` のような
 * カスタム拡張メンバーを許容しており、累積バリデーションの全件をここに列挙する。
 * `properties` は型消去された `Map<String, Any>` なので、ここでの unchecked cast は
 * 「setProperty(ERRORS_PROPERTY, ...) には必ず List<String> を渡す」という
 * このファイル内の運用規約に依存している。
 */
@Suppress("UNCHECKED_CAST")
val ProblemDetail.errors: List<String>?
    get() = properties?.get(ERRORS_PROPERTY) as? List<String>

/**
 * ##### カテゴリ軸による HTTP ステータス決定 (ヘッドライン1)
 *
 * DomainError の直接の許容型は、カテゴリ軸の4つ (ValidationError / NotFoundError /
 * ConflictError / InfrastructureError) とバウンデッドコンテキスト軸の3つ
 * (OrderError / ValueError / SettlementError) を合わせた計7つだが、この when は
 * カテゴリ軸の4分岐だけで `else` 無しに exhaustive になる。
 *
 * これは Kotlin コンパイラが sealed 階層の網羅性検査を「直接の子を見る」のではなく
 * 「具象の末端 (leaf) まで展開してから、各 leaf が何らかの分岐にマッチするか」で
 * 判定しているため: OrderError / ValueError / SettlementError の全ての具象クラスは
 * (domain モジュールの error パッケージ配下を見れば分かる通り) 必ずこの4カテゴリの
 * どれか1つを実装している。
 * そのため4分岐のどれか1つでも欠けると (例えば InfrastructureError を消すと)
 * "'when' expression must be exhaustive. Add the 'is PaymentGatewayUnavailable',
 * 'is RepositoryUnavailable', 'is InfrastructureFailure' branches" という具体的な
 * エラーになることを実装時に実機で確認済み。つまり、新しい OrderError/SettlementError の
 * variant を追加しても「既存のカテゴリを実装している限り」ここは無修正でコンパイルが
 * 通り続ける — カテゴリ軸の設計 (domain/error/DomainError.kt 参照) がそのまま
 * :adapter-web 側の「ステータスコード決定ロジックを増やさなくていい」という利点に
 * 直結している。
 */
private fun DomainError.httpStatus(): HttpStatus =
    when (this) {
        is ValidationError -> HttpStatus.BAD_REQUEST
        is NotFoundError -> HttpStatus.NOT_FOUND
        is ConflictError -> HttpStatus.CONFLICT
        is InfrastructureError -> HttpStatus.SERVICE_UNAVAILABLE
    }

/**
 * `type` URI に使うスラッグ。OrderError は具象 ADT までさらに分解して詳細なスラッグを
 * 割り当て ([orderErrorSlug])、それ以外 (ValueError 由来の ValidationError 等、
 * このモジュールからは category マーカーとしてしか見えないもの) はカテゴリ名で代表する。
 */
private fun DomainError.problemTypeSlug(): String =
    when (this) {
        is OrderError -> orderErrorSlug()
        is ValidationError -> "validation-error"
        is NotFoundError -> "not-found"
        is ConflictError -> "conflict"
        is InfrastructureError -> "infrastructure-error"
    }

/**
 * ##### 具象 ADT (OrderError) を直接分解する、もう1つの網羅性 (ヘッドライン2)
 *
 * 上の [httpStatus] はカテゴリ軸だけを見るため、OrderError に「既存のカテゴリを
 * 再利用しただけ」の新しい variant を追加してもコンパイルは壊れない (意図的な設計)。
 * しかしこの orderErrorSlug は OrderError の5メンバーを1つ1つ名指しで列挙しており、
 * else を書いていないため、たとえ既存カテゴリを再利用しただけの新 variant であっても
 * ここは確実にコンパイルエラーになる。
 *
 * つまり「HTTP ステータスの決定」はカテゴリ軸だけで自動的に正しく動いてよいが、
 * 「この具体的なエラーにどんな type URI (=人間可読な意味) を割り当てるか」は
 * 新しい variant が増えるたびに人間が明示的に決めるべきだ、という設計判断を
 * この2つの when の非対称性で実演している。OrderError 自体は :domain モジュールで
 * 閉じた sealed interface だが、その許容集合は Kotlin のメタデータを通じて
 * モジュール境界を越えて :adapter-web からも認識できるため、この網羅性検査は
 * モジュールを跨いでも問題なく機能する。
 */
private fun OrderError.orderErrorSlug(): String =
    when (this) {
        is OrderError.InvalidOrderLine -> "order-invalid-line"
        is OrderError.OrderNotFound -> "order-not-found"
        is OrderError.InvalidTransition -> "order-invalid-transition"
        is OrderError.PaymentGatewayUnavailable -> "payment-gateway-unavailable"
        is OrderError.RepositoryUnavailable -> "order-repository-unavailable"
    }
