# Arrow スタイルガイド

このテンプレートにおける Arrow (`arrow-core`, `arrow-fx-coroutines`, `arrow-optics`,
`arrow-resilience`) の使い方の指針。「なぜこう書くか」を優先し、API リファレンスの丸写しはしない。

## 境界では `Either`、内部では `either { }`

ユースケースの戻り値・ポート (interface) の戻り値は常に `Either<E, A>` (あるいは
`EitherNel<E, A>`) にする。これはモジュール境界を越えるすべての失敗が、型シグネチャを見るだけで
分かるようにするため。

モジュール内部の実装では、`Either` を `map`/`flatMap` で手動連鎖するのではなく、
`arrow.core.raise.either { }` DSL の中で `.bind()` を使う。

```kotlin
// 推奨: 命令的に読める
fun doSomething(): Either<MyError, Result> = either {
    val a = stepA().bind()
    val b = stepB(a).bind()
    combine(a, b)
}

// 非推奨: 手動の flatMap 連鎖は分岐が増えるほど読みにくくなる
fun doSomethingElse(): Either<MyError, Result> =
    stepA().flatMap { a -> stepB(a).map { b -> combine(a, b) } }
```

`.bind()` は最初に `Left` に出会った時点でその `either { }` ブロック全体を打ち切る
(フェイルファスト)。これは意図的な挙動であり、複数のエラーを溜め込みたい場合は次節の
累積バリデーションを使う。

## `context(raise: Raise<E>)` をあえて使わない理由

Arrow 2.x はコンテキストパラメータ (Kotlin の `context(...)` 構文) を使った
`context(raise: Raise<E>) fun ...` という書き方も提供しているが、このテンプレートでは
採用していない。理由は主に一貫性: `either { }` ブロックの中で `Raise<E>` を暗黙のレシーバとして
使うスタイルに全体を揃えており、コンテキストパラメータ版の記法を混在させると「どちらのスタイルで
新しい関数を書くべきか」がテンプレート利用者にとって新たな意思決定コストになる。加えて、
このテンプレートが対象にしている Kotlin/Arrow の組み合わせでは、`either { }` の方が
IDE の型推論・エラーメッセージの実績が長く枯れている。将来コンテキストパラメータの
エコシステムがより成熟したら、改めて全面採用を検討してよい (ADR 0002 も参照)。

## 累積バリデーション (`EitherNel`) vs フェイルファスト (`Either`)

「入力フィールドの検証は累積、集約レベルのビジネスルールはフェイルファスト」という使い分けを
一貫させている。

- **累積**: `CreateOrderCommand.create` は `orderId`/`customerId`/`lines` の3つを
  `zipOrAccumulate` で並行に検証し、壊れているものが何個あっても1回の呼び出しで
  全部のエラーを返す。`POST /orders` に不正な値を全部盛り込んだリクエストを送ると、
  実際に以下のような `ProblemDetail` が返る (ローカルで実機検証済み)。

  ```json
  {
    "detail": "OrderId must not be blank",
    "status": 400,
    "title": "Bad Request",
    "type": "https://errors.example.com/problems/validation-error",
    "errors": [
      "OrderId must not be blank",
      "CustomerId must not be blank",
      "'bad sku!!' is not a valid SKU (expected [A-Z0-9-]{1,32})",
      "Quantity must be positive, but was -1"
    ]
  }
  ```

- **フェイルファスト**: `Order.create` の重複 SKU チェックや、`OrderTransitions.kt` の
  状態遷移関数群は通常の `Either` を使う。「集約全体で見て初めて分かる整合性違反は、
  最初の1件が見つかった時点で不正確定であり、複数貯める理由が無い」という判断による。

### `.bindNel()` の短絡評価トラップ (実際に踏んだバグ)

`CreateOrderCommand.create` の初期実装は、明細の配列を「先頭要素」と「残りの要素」に分けて
順番に `.bindNel()` していた。これは一見自然に見えるが、実際には **先頭の明細が不正な場合、
残りの明細のエラーを一切収集できずに打ち切られる** というバグを生む。`.bindNel()` は
通常の `Raise` の短絡評価と同じく、失敗した瞬間にその場の処理を止めるため、これは
`.bind()` と同じ「フェイルファスト」の挙動であり、累積を期待するコードの中に紛れ込むと
気づきにくい。

正しい実装は `rawLines.mapOrAccumulate { raw -> ... .bindNel() }` のように、**リスト全体を
1回の `mapOrAccumulate` にかける** こと。`mapOrAccumulate` は各要素を独立に検証し、
成功/失敗を問わず全要素を処理してから、まとめてエラーを返す。「明細Aの SKU が不正、
明細Bの数量が不正」のような複数明細にまたがる不正も、これなら1回の呼び出しで全部拾える。

```kotlin
// NG: 先頭で失敗すると残りのエラーを収集できない
val head = rawLines.head.let { OrderLine.create(...).bindNel() }
val tail = rawLines.tail.map { OrderLine.create(...).bindNel() }

// OK: リスト全体を1回の mapOrAccumulate にかける
rawLines.mapOrAccumulate { raw -> OrderLine.create(raw.sku, raw.quantity, raw.unitPriceMinor, currency).bindNel() }
```

累積バリデーションを新しく書くときは、必ずこのトラップを思い出すこと。「複数要素の集まりを
検証して、失敗した要素をすべて集めたい」と思ったら `mapOrAccumulate` が第一候補である。

## 値クラス + スマートコンストラクタパターン

`OrderId`/`CustomerId`/`Sku`/`Quantity`/`Money`/`MoneyMinor` はすべて `@JvmInline value class`
として実装し、`companion object` の `create(...): Either<ValueError, T>` (スマートコンストラクタ)
を通してしか正当な値を作れないようにする。プライマリコンストラクタ自体は Kotlin の言語仕様上
公開されざるを得ないが、「生の文字列/数値からここに来るには必ず `create` を通る」という
運用規約をコード全体で徹底する。これにより「バリデーション済みの `OrderId`」と「未検証の
`String`」を型レベルで区別でき、ドメインの関数シグネチャに `String` が現れた時点で
「これは何か別の生の入力だ」と分かるようにしている。

## `@optics` (Lens/Traversal)

`domain/order/Order.kt` の `@optics` アノテーションから、KSP (`arrow-optics-ksp-plugin`) が
`Order.address`/`Order.lines` のような `Lens`/`Traversal` を自動生成する
(生成コードは `build/generated/` 配下、ktlint の対象外 --- `template.kotlin-common.gradle.kts`
の `exclude` 設定を参照)。

生成された optics は `domain/order/OrderOptics.kt` で実際に合成して使っている。

- `Lens` の合成例: `Order.address compose ShippingAddress.postalCode` で
  「Order の中の ShippingAddress の中の postalCode」に直接アクセスする合成 Lens を作る。
  `.modify(order, ::toNormalizedPostalCode)` で get → 変換 → set を1回にまとめられる。
- `Traversal` の合成例: `Order.lines.every.compose(OrderLine.unitPrice)` で
  「Order 配下の全 OrderLine の単価」を1つの `Traversal` として扱い、`.modify` で
  全明細に一律の変換 (サーチャージの加算等) を適用する。

optics は「テストの中だけで使われて誰にも実利用されない」状態になりがちなので、
このテンプレートでは意図的にドメインロジックの中で実利用する例を残してある。
新しく `@optics` を追加する際も、実際にその Lens/Traversal を使うコードを1つは
書くことを推奨する (さもないと KSP のコード生成コストだけが増えて何の得もない)。

## 関数型 Bean 登録に教条的にならない

Spring 7 の `BeanRegistrarDsl` は `:bootstrap` の
アノテーション無しクラス (ユースケース実装、ポート実装) の登録に使うが、Spring Security の
`ServerHttpSecurity` DSL やデュアル DataSource 構成のように「フレームワークの流儀に従った方が
得な箇所」では素直に `@Configuration` + `@Bean` を使う。このテンプレートは
「関数型プログラミングに教条的にこだわらない」ことを明示する立場を取っている。
詳細は `docs/adr/0008-hybrid-bean-registration.md` を参照。
