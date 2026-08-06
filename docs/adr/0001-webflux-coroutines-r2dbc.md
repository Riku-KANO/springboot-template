# ADR 0001: WebFlux + Kotlin コルーチン + R2DBC を Web/永続化スタックに採用する

## ステータス

Accepted

## コンテキスト

Web API 層 (`:adapter-web`) と永続化層 (`:adapter-persistence`) の実行モデルとして、以下の選択肢があった。

- Spring MVC + JDBC (ブロッキング、素直で枯れている)
- Spring MVC + 仮想スレッド (Java 21+ の Project Loom。ブロッキングコードのまま並行度を稼げる)
- WebFlux + Reactor (`Mono`/`Flux` の演算子チェーン)
- WebFlux + Kotlin コルーチン (`suspend` 関数、内部的には Reactor 上で動く) + R2DBC

このテンプレートは Java 17 をベースラインにしている (Spring Boot 4.1.0 の要件) ため、仮想スレッドは
選択できない (Loom は Java 21 以降)。純粋な Reactor の演算子チェーンは、Arrow の `Either` や
`either { }` DSL と組み合わせたときに可読性が大きく落ちる (`Mono<Either<E, A>>` を
`flatMap`/`map` で持ち回るコードは、命令的に書けるコルーチンの `either { }` ブロックに比べて
「今どちらの分岐にいるか」が追いにくい)。

## 決定

WebFlux 上で Kotlin コルーチンを使う。コントローラのハンドラメソッドはすべて `suspend fun` にし
(`OrderController` 参照)、WebFlux のフレームワーク側の変換 (`suspend` ハンドラを内部で `Mono` に
アダプトする仕組み) に任せる。永続化層は R2DBC (`r2dbc-postgresql`) を使い、`TransactionalOperator`
の `executeAndAwait` コルーチン拡張関数でトランザクション境界を作る (`R2dbcTxRunner` 参照)。

これにより、ユースケース層 (`:application`) の `suspend fun invoke(...): Either<E, A>` という
シグネチャを、コントローラの中でも `either { ... .bind() }` という命令的な書き方のまま
そのまま呼び出せる。Reactor の演算子チェームに Either を持ち回る必要が無い。

## 帰結

**良い点**

- `either { }` ブロックの中で複数のユースケース呼び出しを `.bind()` で連鎖でき、Reactor の
  `flatMap` ネストより可読性が高い。
- Spring Batch 6 の `JobRepository` は R2DBC 未対応 (ADR 0007 参照) なので、いずれにせよ
  JDBC 用の `DataSource` は必要になる。R2DBC を採用しても JDBC を完全に排除できるわけではない、
  という前提を最初から受け入れている。

**トレードオフ・注意点**

- MDC (SLF4J のスレッドローカル) は、コルーチンが別スレッドで再開すると素朴には伝播しない。
  `CoWebFilter` + `kotlinx.coroutines.slf4j.MDCContext` の組み合わせが必須になる
  (`adapter-web/.../filter/RequestIdWebFilter.kt` の KDoc に実装判断の詳細を記載)。
  `withContext(Dispatchers.Default) { }` のような素朴なディスパッチャ切り替えだけでは
  MDC は運ばれない ---実際に `mono(Dispatchers.Default) { }` で自作した処理が呼び出し元の
  `CoroutineContext` を継承しないことを確認しており、素朴な組み合わせでは動かないことを
  実機で踏んだ。
- チーム内に Reactor/コルーチンの実行モデルの理解が無いと、「なぜ suspend 関数の中で
  `Dispatchers.IO` に切り替える必要がある箇所とそうでない箇所があるのか」でハマりやすい
  (`S3SettlementFileAdapter` の `withContext(Dispatchers.IO)` と `SfnTaskCallbackAdapter` の
  `.await()` の使い分けを参照。前者は同期 API しか無いための退避、後者は真に非同期な
  `CompletableFuture` の橋渡し)。
