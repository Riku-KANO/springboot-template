# テスト戦略

## テストランナー: JUnit 6 (Kotest はライブラリとしてのみ)

このテンプレートのテストはすべて JUnit 6 (`org.junit.jupiter`, Spring Boot 4.1.0 の管理下と
同一バージョン) の上で動く。Kotest は `kotest-property` (`Arb`/`checkAll`) だけをライブラリとして
使い、テストランナーとしては使っていない。理由 (`kotest-extensions-spring`/
`kotest-assertions-arrow` の互換性問題) は `docs/adr/0005-junit-over-kotest.md` を参照。

`Either` に対するアサーション (`shouldBeRight()`/`shouldBeLeft()`/`shouldBeLeftOfType<T>()`) は
`:domain` の `testFixtures` (`domain/src/testFixtures/kotlin/.../testfixtures/EitherAssertions.kt`)
に自前実装がある。他モジュールのテストは `testImplementation(testFixtures(project(":domain")))`
でこれを再利用する。

## テストの内訳 (160 件、0 failures)

| モジュール | 件数 | 何を検証しているか |
|---|---|---|
| `:domain` | 48 | 値オブジェクトのスマートコンストラクタ、`OrderTransitions` の状態遷移 (`else` を書かないことで保証される網羅性)、`reconcile` の判定ロジック、`@optics` の Lens/Traversal |
| `:application` | 46 | ユースケースのオーケストレーション、累積バリデーション、cursor pagination、注文ライフサイクル一巡 |
| `:adapter-persistence` | 19 | R2DBC マッピング、楽観ロック、cursor検索、Flyway、`R2dbcTxRunner` のロールバック (Testcontainers Postgres) |
| `:adapter-web` | 34 | 全HTTP契約、一覧の境界値、エラーマッピング、OAuth2 scope認可、`RequestIdWebFilter` |
| `:adapter-messaging` | 10 | 必須設定の起動時validation、`SfnTaskCallbackAdapter`/`S3SettlementFileAdapter` (Testcontainers LocalStack) |
| `:batch` | 2 | `settlementReconciliationJob` の end-to-end 実行 (Testcontainers Postgres) |
| `:bootstrap` | 1 | composition rootを全て組み立て、実Postgresで起動・作成・参照するスモークテスト |

## 何が Spring コンテキストを必要とし、何が不要か

- **Spring コンテキスト不要 (プレーンな JUnit + mockk)**: `:domain`/`:application` の
  全テスト。Spring への依存が無いモジュールなので、テストも当然 Spring 抜きで書ける
  (起動が速く、フィードバックループが短い)。ユースケースのテストはポート (interface) を
  mockk でモックし、ビジネスロジックだけを検証する。
- **Spring コンテキストが必要 (`@SpringBootTest`/`@WebFluxTest` 等)**: `:adapter-*` の
  マッピング/配線を検証するテスト。ただし `:adapter-web` の一部 (`@WebFluxTest`, Boot 4.1 では
  `spring-boot-starter-webflux-test` という専用 starter が必要) はコントローラ単体の
  スライステストで済ませ、フルコンテキスト起動は最小限に抑える。
- **Testcontainers が必要**: 実際の PostgreSQL/LocalStack に対する挙動を検証するテスト
  (`:adapter-persistence` の R2DBC マッピング、`:adapter-messaging` の S3/Step Functions
  連携、`:batch` の end-to-end 実行)。Testcontainers 2.x はアーティファクト名が変わっている点に
  注意 (`testcontainers-postgresql`/`testcontainers-localstack`/`testcontainers-junit-jupiter`)。

## `kotest-property` の使いどころ

`Arb`/`checkAll` は ADT の「性質」を検証するのに向いている。例えば:

- `Money` の加算が可換であること (`a + b == b + a` が任意の非負の値の組で成り立つ)
- `Sku.create` が特定の正規表現 (`[A-Z0-9-]{1,32}`) を満たす文字列でのみ成功すること
- スマートコンストラクタが「境界値 (最大長ちょうど、最大長+1)」で正しく振る舞うこと

個々の具体例を手で書き並べるより、生成された多数の入力に対して不変条件を検証する方が
バグの見落としが減る場面で使う。全てのテストをプロパティベースにする必要はない
(具体例ベースのテストの方が「何を検証しているか」が読みやすい場面も多い)。

## `R2dbcTxRunner` のロールバック検証

`TransactionalOperator.executeAndAwait` は **例外を投げた場合にのみ** ロールバックする。
:application 層のユースケースは例外を投げない設計 (`Either.Left` で失敗を表現する) なので、
`R2dbcTxRunner` は block の結果が `Left` だった場合に明示的に `ReactiveTransaction.setRollbackOnly()`
を呼ぶ。この挙動は「実際に1行書き込んでから `Left` を返し、コミット後にその行が
存在しないことを確認する」という Testcontainers Postgres 経由の統合テストで担保している
(`:adapter-persistence` の該当テスト)。単体テストでは検証できない類のバグ (トランザクション
境界の実際の挙動) なので、意図的に実データベースに対するテストにしている。

## 何をテストしないか / テストの限界

- **Step Functions のステートマシン全体の振る舞い** (`infra/terraform/statemachine/*.asl.json`)
  は、このテンプレートの自動テストの対象外。ローカルでは docker-compose + LocalStack 経由で
  手動検証している (`docs/local-development.md` 参照) だけであり、CI 上で ASL 定義の
  実行結果を自動検証するテストは無い。ASL の構文的な妥当性 (JSON として parse できるか) の
  確認だけは `python -m json.tool`/`jq` で行える。
- **Pattern B (ECS RunTask) の実際の起動**は、LocalStack Community が ECS を
  サポートしないためローカルでは一切検証できない。この事実は誇張せずそのまま
  `docs/local-development.md` に明記している。
