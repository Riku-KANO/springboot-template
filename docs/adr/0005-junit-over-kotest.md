# ADR 0005: テストランナーは JUnit 6 とし、Kotest はライブラリとしてのみ使う

## ステータス

Accepted

## コンテキスト

Kotlin エコシステムでは Kotest (`StringSpec`/`FunSpec` 等の DSL でテストを書き、独自ランナーで
実行する) が広く使われている。しかし本テンプレートのスタック (Spring Boot 4.1.0 / Kotlin 2.3.21 /
Arrow 2.2.2 / Spring 7) と Kotest のエコシステムを実際に組み合わせようとすると、以下の非互換が
判明した。

- `kotest-extensions-spring` の最新版 (1.3.0) は Kotest 5.8.1 / Spring 5.3.36 / Kotlin 1.8.21 で
  ビルドされている。Spring Framework 7 / Kotlin 2.3.21 とは互換性が無い。
- `kotest-assertions-arrow` (2.0.0) は Kotest 5 系 / Arrow 2.0 系向けであり、本テンプレートの
  Kotest 6 系 (`kotest-property`) / Arrow 2.2.2 の組み合わせとは合わない。

つまり「Kotest をテストランナーとして採用する」選択をすると、Spring 統合や Arrow アサーションの
どちらか (あるいは両方) を諦めるか、非公式・非互換な組み合わせを無理に使うことになる。

## 決定

テストランナーは JUnit 6 (Spring Boot 4.1.0 が管理する `junit-jupiter` と同一バージョン) とする。
Kotest は **ランナーとしては使わず**、`kotest-property` (`Arb`/`checkAll` によるプロパティベース
テスト) だけをライブラリとして使う。これは純粋にジェネレータ/シュリンク機構のライブラリであり、
テスト実行そのものは JUnit の `@Test` メソッドの中から `checkAll { ... }` を呼ぶ形になるため、
テストランナーの選択とは独立に導入できる。

`Either` に対するアサーション (`shouldBeRight()`/`shouldBeLeft()`/`shouldBeLeftOfType<T>()`) は
`kotest-assertions-arrow` を使わず、`:domain` の `testFixtures` (`EitherAssertions.kt`) に
自前で数行実装する。JUnit は「`AssertionError` を投げれば失敗」という規約だけを守ればよいため、
どのアサーションライブラリを使っていても (あるいは自作していても) 問題なく統合できる。

## 帰結

**良い点**

- サードパーティの互換性マトリクスに振り回されずに済む。`Either` のアサーション3関数を
  自前で持つコストは、`kotest-assertions-arrow` の互換バージョンが出るのを待つコストより
  はるかに小さい。
- `kotest-property` の `Arb`/`checkAll` は ADT の性質検証 (`Money` の加算が可換である、
  `Sku` のスマートコンストラクタが特定の正規表現を必ず満たす等) に使える。テストランナーの
  選択と切り離してあるので、JUnit の `@Test` メソッドの中に自然に混在させられる。

**トレードオフ・注意点**

- Kotest の `StringSpec`/`describe`/`context` のようなネスト構造化された記述力は失われる。
  JUnit の `@Nested` である程度代替できるが、Kotest の DSL ほど自由ではない。
- 将来 `kotest-extensions-spring` や `kotest-assertions-arrow` が本テンプレートのバージョン
  スタックに追従したとしても、テストランナーを乗り換えるのは全テストファイルに影響する
  大きな変更になる。乗り換えるかどうかは、そのときの互換性状況と移行コストを踏まえて
  改めて判断すること。
