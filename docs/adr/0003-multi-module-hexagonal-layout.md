# ADR 0003: マルチモジュール構成でヘキサゴナルアーキテクチャを強制する

## ステータス

Accepted

## コンテキスト

「ドメイン層は特定のフレームワークに依存しない」という原則は、レビューや ArchUnit/Konsist のような
実行時・テスト時のルールチェックだけで維持しようとすると、レビュアーの見落としやテストの
メンテナンス忘れで徐々に形骸化しがちである。

## 決定

Gradle のマルチモジュール構成そのものでこの原則を強制する。

```
:domain           -- Spring 依存ゼロ。Arrow のみ。
:application      -- Spring 依存ゼロ。:domain のみに依存。ユースケース + ポート (interface) を置く。
:adapter-persistence -- :domain, :application に依存。R2DBC/Flyway 実装。
:adapter-web       -- :domain, :application に依存。WebFlux コントローラ、Security、OpenAPI。
:adapter-messaging -- :domain, :application に依存。SQS/S3/Step Functions 連携。
:batch             -- :domain, :application に依存。Spring Batch のジョブ/ステップ定義。
:bootstrap         -- 全モジュールに依存する composition root。bootJar を生成する唯一のモジュール。
```

`:domain` と `:application` が適用する `template.kotlin-pure` (build-logic) は Spring 関連の
依存を一切追加しない。これにより、誰かが `:domain` の中で `org.springframework.*` を import
しようとした瞬間、それは **lint 警告ではなくコンパイルエラー** になる (クラスパスにそのクラスが
存在しないため)。ArchUnit や Konsist のような「実行してはじめて検出できる」仕組みが不要なのは
このためである。

依存の向きも Gradle の `project(":xxx")` 宣言そのものが強制する。例えば `:adapter-messaging`
から `:batch` への project 依存は settings.gradle.kts のモジュールグラフに存在せず、
追加もされていない (`SettlementTaskListener.kt` のコメント参照)。両者は Spring Batch という
共通のサードパーティ型 (`Job`/`JobOperator`) だけに依存し、実際の Bean 解決は `:bootstrap` の
単一 `ApplicationContext` に委ねている。

## 帰結

**良い点**

- 「ドメインが Spring に汚染されていないか」を確認するのに、専用のテストやレビューの
  チェックリストが要らない。`./gradlew :domain:compileKotlin` が通ること自体が証明になる。
- モジュール境界がそのままチームの認知的な境界にもなる。「このロジックは `:domain` に
  置けるはずのビジネスルールか、それとも特定インフラの都合か」を、置き場所を決める段階で
  自然に問い直させる。

**トレードオフ・注意点**

- モジュール数が多い分、Gradle の設定 (build-logic の precompiled script plugin) が複雑になる。
  小規模なプロトタイプではオーバーヘッドになりうる (このテンプレートは「実運用を想定した
  ある程度の規模のサービス」を前提にしており、使い捨てのプロトタイプにこの構成をそのまま
  適用する必要は無い --- 過剰なら `:adapter-*` を統合する等、テンプレートを簡略化してよい)。
- モジュールを跨いだ型の共有 (`OrderError` を `:adapter-web` から見る等) は、Kotlin の
  sealed interface が「直接の実装/継承先は同じパッケージに置く」制約と、モジュール間の
  可視性のルールを両方理解している必要があり、初見では「なぜこのエラー型はここに置けないのか」
  で立ち止まりやすい。
