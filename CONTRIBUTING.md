# コントリビューションガイド

このリポジトリは Spring Boot + Kotlin + Arrow の関数型テンプレートです。ここでは
「このテンプレート自体」に変更を加える (バグ修正、機能追加、ドキュメント整備) 際の
進め方をまとめます。テンプレートから自分のプロジェクトを始める方法は
`docs/how-to-use-this-template.md` を参照してください。

## 開発環境のセットアップ

- JDK 17 (Temurin 推奨)
- Docker / Docker Compose (Testcontainers によるローカルの統合テストに必須)
- Terraform 1.14 系 (`infra/terraform/` を変更する場合)

```bash
git clone <this-repo>
cd springboot-template
./gradlew build
```

初回は依存関係のダウンロードとテストの実行 (Testcontainers による Postgres/LocalStack の
起動を含む) で数分かかります。

## ビルド・テスト・静的解析コマンド

```bash
./gradlew build                       # 全モジュールのコンパイル + テスト (160件)
./gradlew ktlintCheck spotlessCheck    # 静的解析 (このプロジェクトに detekt は無い。ADR 0009 参照)
./gradlew ktlintFormat                 # ktlint の自動整形
./gradlew :bootstrap:bootRun --args='--spring.profiles.active=local'  # ローカル起動
```

`docs/local-development.md` に docker-compose の起動から Web API/バッチ/Step Functions の
動作確認までの具体的な手順があります。

## コードを変更する際の指針

- **モジュール境界を尊重する。** `:domain`/`:application` に Spring への依存を持ち込まない
  (`docs/architecture.md`, ADR 0003 参照)。ビルドが通ること自体がこの制約の検証になっている。
- **Arrow のスタイルガイドに従う。** `docs/arrow-style-guide.md` を参照。特に累積バリデーション
  (`mapOrAccumulate`) と `.bindNel()` の短絡評価トラップは、新しいバリデーションロジックを
  書く前に必ず読むこと。
- **バージョンを個別に上げない。** `gradle/libs.versions.toml` の Arrow/Kotlin/KSP は
  相互に依存し合っている (ADR 0004)。どれか1つだけを上げるとビルドは通るのに
  `@optics` のコード生成が壊れる、という分かりにくい壊れ方をする。
- **アーキテクチャ上の大きな決定は ADR を書く。** `docs/adr/` に既存の ADR がある。
  新しい番号を採番し、同じフォーマット (ステータス/コンテキスト/決定/帰結) で追加する。
- **設定ファイル (`application*.yml`) を変更したら、対応する `infra/terraform/` の
  変数名・プロパティ名も一致しているか確認する。** 例えば `settlement.s3.bucket-name` の
  プロパティ名を変えたら `infra/terraform/envs/*/terraform.tfvars` の
  `settlement_bucket_name` も追随させる。

## テストを書く

- テストランナーは JUnit 6。Kotest は `kotest-property` (`Arb`/`checkAll`) のみライブラリとして
  使う (ADR 0005, `docs/testing-strategy.md`)。
- `Either` のアサーションは `:domain` の `testFixtures`
  (`shouldBeRight()`/`shouldBeLeft()`/`shouldBeLeftOfType<T>()`) を使う。
- 新しいユースケース/ドメインロジックを追加したら、少なくとも `:domain`/`:application` レベルの
  テスト (Spring コンテキスト不要、高速) を書く。実インフラとの結合が絡む変更
  (永続化マッピング、AWS 連携) は Testcontainers を使った統合テストを追加する。

## Terraform を変更する

`infra/terraform/envs/{dev,stg,prod}` は独立したルートモジュールです。変更後は
少なくとも以下を実行して壊れていないことを確認してください。

```bash
cd infra/terraform
terraform fmt -recursive
cd envs/dev && terraform init -backend=false && terraform validate
cd ../stg  && terraform init -backend=false && terraform validate
cd ../prod && terraform init -backend=false && terraform validate
```

`prod` の削除保護系の設定 (`multi_az`/`deletion_protection`/`skip_final_snapshot` 等) は
意図的に `terraform.tfvars` ではなく `main.tf` に直接ハードコードしてあります。これらを
`tfvars` 経由で変更可能にするような PR は、変更理由を明確にレビューで説明してください。

`infra/terraform/statemachine/*.asl.json` を変更したら、最低限 JSON として parse できることを
確認する。

```bash
python -m json.tool infra/terraform/statemachine/settlement-reconciliation.asl.json > /dev/null
python -m json.tool infra/terraform/statemachine/settlement-reconciliation-sqs.asl.json > /dev/null
```

## コミット・プルリクエスト

- コミットメッセージは変更の "why" が分かるように書く。
- 1つの PR は1つの関心事にまとめる (アーキテクチャ変更とフォーマット変更を混ぜない)。
- CI (`.github/workflows/ci.yml`) がグリーンであることを確認する。`infra/**` を変更した PR は
  `.github/workflows/terraform-plan.yml` の fmt/validate も通ること。

## ドキュメントを変更する

- ドキュメントは日本語で書く (このプロジェクトの利用者層に合わせている)。コード識別子・
  Terraform リソース名・YAML キー・ファイルパスは英語のまま。
- ドキュメントに書く内容 (プロパティ名、プロファイル名、コマンド例) は必ず実際のソースコードと
  照合してから書く。動かしていない・確認していないコマンド例は書かない。
