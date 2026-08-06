package com.example.template.adapter.persistence.tx

import arrow.core.Either
import com.example.template.application.port.TxRunner
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * [TxRunner] の R2DBC 実装。
 *
 * ##### なぜ `Left` を明示的にロールバックさせる必要があるか
 * Spring の `TransactionalOperator.executeAndAwait` (spring-tx のコルーチン拡張関数) は、
 * ラムダが **例外を投げた場合にのみ** ロールバックする。しかし :application 層の
 * ユースケースは例外を投げない設計 (すべての失敗を `Either.Left` で表現する) であり、
 * block が `Left` を返しても `executeAndAwait` からすれば「ラムダは正常に完了した」
 * ようにしか見えず、何もしなければそのままコミットされてしまう
 * (実際に返ってきた値が Left かどうかは `executeAndAwait` の関知するところではない)。
 *
 * これを防ぐため、block の実行結果が `Left` だった場合は
 * `ReactiveTransaction.setRollbackOnly()` を呼んでこのトランザクションを
 * 「ロールバック確定」としてマークしたうえで、`Left` 自体は変更せずそのまま
 * 呼び出し元へ返す。`setRollbackOnly()` は「例外を投げずにロールバックだけを予約する」
 * ための API であり、ここでもし代わりに例外を投げてしまうと、呼び出し元が
 * 本来受け取るべき `Left` の中身 (どのエラーだったか) が失われてしまうため使えない。
 *
 * この「Left になったら rollback-only を立てるが、戻り値としては Left をそのまま返す」
 * という2つの動作が独立している (rollback-only はコミット可否だけに効き、戻り値には
 * 影響しない) ことが、このクラスが正しく動くための前提。ロールバックされたかどうかは
 * [tx.R2dbcTxRunnerTest] のロールバックテストで、実際に行を書き込んでから
 * `Left` を返し、コミット後にその行が存在しないことを検証することで担保している。
 */
class R2dbcTxRunner(
    private val transactionalOperator: TransactionalOperator,
) : TxRunner {
    override suspend fun <E, A> transactional(block: suspend () -> Either<E, A>): Either<E, A> =
        transactionalOperator.executeAndAwait { reactiveTransaction ->
            val result = block()
            if (result.isLeft()) {
                reactiveTransaction.setRollbackOnly()
            }
            result
        }
}
