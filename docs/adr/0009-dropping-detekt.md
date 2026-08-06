# ADR 0009: detekt を静的解析から外し、ktlint + spotless のみにする

## ステータス

Accepted

## コンテキスト

Kotlin プロジェクトの静的解析ツールとしては detekt が広く使われており、当初はこのテンプレートにも
導入を検討した。しかし `build-logic` に実際に組み込んで検証したところ、以下のハードエラーで
ビルドが失敗した。

```
detekt was compiled with Kotlin 2.0.21 but is currently running with 2.3.21. This is not supported.
```

detekt 1.23.8 が内部にバンドルする Kotlin コンパイラフロントエンドが Kotlin 2.3.21 と非互換で
あることが根本原因であり、`ktlint_official` のコードスタイル設定や `allRules=false` のような
detekt 側のオプション調整では回避できなかった。

## 決定

detekt は採用しない。静的解析は ktlint (`org.jlleitschuh.gradle.ktlint`, フォーマット + 一部の
lint ルール) と spotless (`com.diffplug.spotless`, `*.gradle.kts` のフォーマット) の組み合わせだけで
運用する。`gradle/libs.versions.toml` には detekt のバージョン (`1.23.8`) と
`detekt-gradle-plugin` の座標を **記録としてだけ** 残し、実際にはどの `build.gradle.kts` にも
適用しない。

## 帰結

**良い点**

- CI のビルドが detekt のバージョン非互換で赤くなる、という不安定要因を最初から排除できる。
- ktlint + spotless という2つだけのシンプルな構成なので、`./gradlew ktlintCheck spotlessCheck`
  という短いコマンドで全静的解析が完結する (`.github/workflows/ci.yml` 参照)。

**トレードオフ・注意点**

- detekt が担っていた「複雑度 (cyclomatic complexity)」「長すぎる関数」「マジックナンバー」
  といった、フォーマット以上のコード品質ルールは何も持たない。ktlint はコードスタイル
  (インデント、import 順序、行長等) の統一が主目的であり、detekt が持つような設計上の
  ルール検査は代替できていない。この種のルールが必要になった場合、以下のいずれかを
  検討すること。
    1. detekt の新しいメジャーバージョンが Kotlin 2.3.x 系との互換性を公式にアナウンスする
       のを待って再導入する。
    2. detekt 以外の Kotlin 2.3.x 対応済みの静的解析ツールを別途調査する。
    3. コードレビューのチェックリストで人手により補う。
- 将来 Kotlin をさらに新しいバージョンに上げる際は、detekt が「まだ非互換」なのか
  「対応版が出た」のかを毎回確認し直す必要がある (ADR 0004 のバージョン連動の話とは別に、
  detekt 単体の対応状況も追跡対象になる)。
