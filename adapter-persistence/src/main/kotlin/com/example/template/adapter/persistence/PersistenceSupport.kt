package com.example.template.adapter.persistence

import org.springframework.r2dbc.core.DatabaseClient

/**
 * R2DBC / SQL 例外を Either の世界へ変換するためのアダプタ共通ヘルパー。
 *
 * [order.OrderRepositoryAdapter] / [settlement.SettlementRepositoryAdapter] のどちらも、
 * ドライバが投げる例外 (接続不可・SQL 構文エラー等) を `Either.catch { }` で捕まえた後、
 * そのまま `Throwable` を Left に詰めるのではなく、この関数で人間が読めるメッセージへ変換する。
 * (`OrderError.RepositoryUnavailable` / `SettlementError.InfrastructureFailure` は
 * どちらも単なる `String` を持つ設計なので、ここで一度メッセージ化しておく)
 */
internal fun Throwable.describeForRepository(): String = "${this::class.simpleName}: ${message ?: "(no message)"}"

/**
 * `DatabaseClient.GenericExecuteSpec.bind` は non-null 専用、`null` を渡したい場合は
 * `bindNull(name, type)` を別途呼ぶ必要がある (Javadoc 参照)。呼び出し側で
 * `if (value == null) ... else ...` を毎回書かずに済むよう、ここに1箇所へ集約する。
 * OrderStatus のペイロード列 (paidAt, trackingNumber 等) はバリアントごとに
 * ほとんどが NULL になるため、この関数が無いと呼び出し側のコード量が倍増する。
 */
internal fun DatabaseClient.GenericExecuteSpec.bindNullable(
    name: String,
    value: Any?,
    type: Class<*>,
): DatabaseClient.GenericExecuteSpec = if (value == null) bindNull(name, type) else bind(name, value)
