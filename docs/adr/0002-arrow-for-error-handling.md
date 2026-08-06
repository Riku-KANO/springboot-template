# ADR 0002: Arrow (`Either`/`EitherNel`) をエラーハンドリングの基盤にする

## ステータス

Accepted

## コンテキスト

Kotlin の例外機構 (checked exception が無い) だけでは、「この関数がどんな失敗をしうるか」が
シグネチャから読み取れない。ドメインの失敗 (注文が見つからない、状態遷移が不正、決済ゲートウェイが
落ちている等) を例外として実装すると、呼び出し側は try/catch で捕まえるかどうかを型システムに
強制されず、握りつぶし忘れが起きやすい。一方で全てを `Result<T>` のような単純な二値に押し込めると、
「バリデーションエラーを複数まとめて返したい」という要件 (このテンプレートのヘッドライン機能である
累積バリデーション) を表現できない。

## 決定

Arrow の `Either<E, A>` を境界(ユースケースの戻り値、リポジトリ/ポートの戻り値)の標準的な失敗表現に
採用し、モジュール内部の合成には `either { }` / `arrow.core.raise.Raise` DSL を使う。累積が必要な
箇所 (`CreateOrderCommand.create`) は `EitherNel<E, A>` + `zipOrAccumulate` / `mapOrAccumulate` を使う。

エラー型そのものは「2軸の ADT」として設計する (`domain/error/DomainError.kt` 参照)。

- 軸1 (バウンデッドコンテキスト軸): `OrderError` / `SettlementError` / `ValueError` という
  sealed interface。ユースケース層はこの軸で `when` 分岐し、業務判断を行う。
- 軸2 (カテゴリ軸): `ValidationError` / `NotFoundError` / `ConflictError` / `InfrastructureError`
  という HTTP ステータス相当のマーカー。`:adapter-web` はこの軸だけで `when` 分岐し、
  404 を返すか 409 を返すかを決める。

具象エラークラスは両方の軸を実装する (例: `OrderError.OrderNotFound : OrderError, NotFoundError`)。

`context(raise: Raise<E>)` パラメータ (Arrow 2.x が提供する Kotlin のコンテキストパラメータ機能を
使った代替記法) は意図的に採用していない。理由は ADR 0004 (Arrow/Kotlin/KSP のバージョン固定) と
関連する: `either { }` ラムダ内で `Raise<E>` を暗黙のレシーバとして使う既存のスタイルで
一貫させており、コンテキストパラメータと `either { }` DSL の2つの書き方が混在すると、
「どちらのスタイルで書くべきか」がテンプレート利用者にとって新たな意思決定コストになる。

## 帰結

**良い点**

- `DomainError` のカテゴリ軸に対する `when` は、Kotlin コンパイラが sealed 階層を「具象の
  末端まで展開してから網羅性を判定する」ため、`ValidationError`/`NotFoundError`/`ConflictError`/
  `InfrastructureError` の4分岐だけで (`else` 無しに) exhaustive になる。新しい `OrderError`/
  `SettlementError` の variant を追加しても、既存のカテゴリを実装している限り
  `DomainErrorProblemMapper.httpStatus()` は無修正でコンパイルが通り続ける。
- 一方 `orderErrorSlug()` (type URI のスラッグ決定) は `OrderError` の具象クラスを1つずつ
  列挙しており、新しい variant を追加すると確実にコンパイルエラーになる。「ステータスコードは
  自動でよいが、人間可読な type URI は都度決めるべき」という設計判断を、この2つの `when` の
  非対称性で実演している。

**トレードオフ・注意点**

- `.bindNel()` は失敗した瞬間にその場の処理を打ち切る (通常の `Raise` の短絡評価)。
  `NonEmptyList` を「先頭と残り」に分けて順に `.bindNel()` すると、先頭が不正だった場合に
  残りのエラーを一切収集できずに打ち切られてしまう --- 実際にこの実装で一度そのバグを踏んだ
  (`CreateOrderCommand.kt` の `validateLines` の KDoc 参照)。対処は `rawLines.mapOrAccumulate { }`
  で全要素を独立に検証すること。累積バリデーションを書く際は必ずこのトラップを意識すること。
- `Either` は例外ではないため、`try/catch` に慣れたエンジニアには「エラーケースの握りつぶし忘れ」が
  むしろ逆に起きやすい (`.bind()` し忘れて `Either<E, A>` のままメソッドの戻り値型に紛れ込ませる、
  といったミス)。ktlint/spotless では検出できないため、コードレビューで見る他ない。
