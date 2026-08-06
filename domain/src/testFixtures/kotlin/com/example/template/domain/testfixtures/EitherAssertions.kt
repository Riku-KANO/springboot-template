package com.example.template.domain.testfixtures

import arrow.core.Either

/*
 * kotest-assertions-arrow は Kotest 5 / Arrow 2.0 系にピン留めされており、
 * このリポジトリの Kotest 6 (property のみ) / Arrow 2.2.2 の組み合わせとは
 * 互換性がない。そのため Either 用のアサーションはここで自前に用意する
 * (JUnit の `@Test` は「AssertionError を投げれば失敗」という規約なので、
 * どのアサーションライブラリを使っていても問題なく統合できる)。
 */

/** Right であることを検証し、中身を取り出す。Left だった場合はテストを失敗させる。 */
fun <A> Either<*, A>.shouldBeRight(): A =
    when (this) {
        is Either.Right -> value
        is Either.Left -> throw AssertionError("expected Either.Right but was Either.Left($value)")
    }

/** Left であることを検証し、中身を取り出す。Right だった場合はテストを失敗させる。 */
fun <E> Either<E, *>.shouldBeLeft(): E =
    when (this) {
        is Either.Left -> value
        is Either.Right -> throw AssertionError("expected Either.Left but was Either.Right($value)")
    }

/** Left であり、かつその中身が型 [T] であることを検証し、中身を取り出す。 */
inline fun <reified T : Any> Either<*, *>.shouldBeLeftOfType(): T =
    when (this) {
        is Either.Left ->
            value as? T
                ?: throw AssertionError("expected Either.Left of type ${T::class.simpleName} but was Left($value)")
        is Either.Right -> throw AssertionError("expected Either.Left but was Either.Right($value)")
    }
