# このテンプレートの使い方

新しいプロジェクトをこのテンプレートから始める際のチェックリスト。

## 1. ベースパッケージ名を変更する

全モジュールのソースが `com.example.template` 配下に置かれている。IDE の一括リネーム
(IntelliJ IDEA なら パッケージを右クリック → Refactor → Rename) を使い、`com.example.template`
を実際の組織のパッケージ名 (例: `com.yourcompany.yourservice`) に置き換える。手作業で
`grep`/`sed` する場合は、少なくとも以下を必ず確認すること。

- 各モジュールの `src/main/kotlin` 配下のパッケージ宣言と import
- `bootstrap/src/main/kotlin/.../TemplateApplication.kt` の `@SpringBootApplication(scanBasePackages = [...])`
  --- ここを変え忘れると `NoSuchBeanDefinitionException` の連鎖で起動が失敗する
- `bootstrap/src/main/resources/application.yml` の `logging.level` キー
  (`com.example.template: INFO` のような箇所)
- `adapter-persistence/src/main/resources/db/migration/*.sql` のコメント中の言及(実害は無いが
  検索性のため合わせておくとよい)
- テストコード全般 (`src/test`, `src/testFixtures`)

パッケージ名を変えたら `./gradlew build` を1回通し、全モジュールがコンパイルできることを
確認する。

## 2. アプリケーション名・リソース名を変える

`bootstrap/src/main/resources/application*.yml` の `spring.application.name`
(`springboot-template`)、`settlement.s3.bucket-name`/`settlement.sqs.queue-name`
(`template-settlements-{env}`/`template-settlement-tasks-{env}`) を、実プロジェクトの
命名規則に合わせて変更する。同時に `infra/terraform/envs/*/terraform.tfvars` の
`settlement_bucket_name`/`settlement_queue_name` を **同じ値に** 揃えること (アプリの
プロパティと Terraform が作るリソース名が食い違うと、実行時に
`SettlementError.InfrastructureFailure` (バケット/キューが見つからない) になる)。

S3 バケット名は AWS 全体でグローバルに一意である必要がある。既定値のまま複数の
利用者が同時にこのテンプレートを apply すると衝突するので、必ず組織名やアカウント ID を
含めた一意な値に変更すること。

## 3. 不要なサンプルを削除する (任意)

このテンプレートは「EC 注文 + 日次消込バッチ」という具体的なドメインを題材にした
サンプル実装を全モジュールに持っている。**アーキテクチャ (モジュール分割、エラー ADT の
2軸設計、WebFlux + コルーチンの配線、Batch のデュアル DataSource 構成など) だけを流用し、
ドメインロジック自体は書き直したい** 場合、以下が削除・置き換えの対象になる。

- ドメインロジックそのもの: `domain/.../order/`, `domain/.../settlement/`,
  `domain/.../shared/` (値オブジェクト), `domain/.../error/OrderError.kt` と
  `SettlementError.kt` (`DomainError.kt`/`ValueError.kt` の型分類パターンは流用価値が高い
  ので残すことを推奨)
- ユースケース: `application/.../order/`, `application/.../settlement/`
- Web: `adapter-web/.../order/` (コントローラ/DTO), `problem/` パッケージの
  `DomainErrorProblemMapper`/`orderErrorSlug()` はカテゴリ軸/具象軸それぞれのパターンを
  示す実例として参考にしつつ、自分のドメインのエラー型に合わせて書き直す
- 永続化: `adapter-persistence/.../order/`, `settlement/`, 対応する Flyway マイグレーション
  (`V1`〜`V4`。`V0`/`V5` は Spring Batch のメタデータなので、バッチ機能自体を残すなら削除しない)
- メッセージング: `adapter-messaging/.../s3/`, `sqs/`, `sfn/` (Step Functions 連携の
  枠組み自体を残すなら、中身 (S3 の消込ファイル形式など) だけ差し替える)
- バッチ: `batch/.../settlement/` 一式

**残すことを強く推奨する部分** (ドメインを問わず再利用価値が高い):

- モジュール分割そのもの (`settings.gradle.kts`, `build-logic/`)
- `DomainError`/`ValidationError`/`NotFoundError`/`ConflictError`/`InfrastructureError` の
  2軸パターン (`docs/arrow-style-guide.md`, ADR 0002)
- `TxRunner` ポート + `R2dbcTxRunner` の rollback-only パターン
- `RequestIdWebFilter` + `MDCContext` の相関 ID 伝搬
- ハイブリッドな Bean 登録方針 (ADR 0008)

## 3.5 Step Functions/バッチ機能自体が不要な場合

決済消込バッチのような非同期バッチ処理そのものが不要なプロジェクトでは、以下を丸ごと
削除できる。

- `:batch` モジュール (`settings.gradle.kts` の `include(...)` からも外す)
- `adapter-messaging/.../sqs/`, `sfn/` (S3 連携だけ残したいなら `s3/` は残せる)
- `infra/terraform/modules/{ecs-task,sfn-state-machine,sqs-queue}` とそれらを参照している
  `infra/terraform/envs/*/main.tf` の該当ブロック
- `infra/terraform/statemachine/*.asl.json`
- `docker/localstack-init/init-aws.sh` の SQS/Step Functions 関連部分 (S3 だけ残すなら
  バケット作成部分は残す)

## 4. 初回デプロイまでの流れ

1. **リモートステートの器を用意する** (Terraform 管理外、手動で1回だけ)。
   `infra/terraform/envs/dev/backend.tf` のコメントに具体的な `aws s3api create-bucket`/
   `aws dynamodb create-table` コマンドを記載してある。stg/prod も同じバケットの
   別 key に相乗りしてよい (この例では相乗りする前提で書いてある)。
2. **`terraform.tfvars` を実際の値に書き換える** (`account_id`, `github_repository`,
   `settlement_bucket_name` 等。プレースホルダのままでは apply できない、あるいは
   意図しないアカウント/バケットに向いてしまう)。
3. **dev から順に、人手の AWS 認証情報 (管理者権限、または十分な権限を持つロール) で
   `terraform apply` する。** このとき `create_github_oidc_provider = true`
   (dev の `terraform.tfvars` の既定値) により、GitHub Actions 用の OIDC プロバイダと
   ロールも同時に作られる。
4. **OIDC ブートストラップ (鶏と卵の解消)**: dev の apply が終わったら、
   `terraform output github_actions_role_arn` (と ECR リポジトリ URL、リージョン) を
   GitHub リポジトリの `Settings > Secrets and variables > Actions > Variables` に
   `AWS_GITHUB_ACTIONS_ROLE_ARN` / `ECR_REPOSITORY_URL` / `AWS_REGION` として登録する。
   ここまでやって初めて `.github/workflows/docker-build-push.yml` が動くようになる
   (このワークフロー自身がまだ存在しないロールを使って自分を bootstrap することはできない、
   という制約は最初から織り込み済み)。
5. **stg/prod は `create_github_oidc_provider = false` のまま** (既定値) apply する。
   OIDC プロバイダは AWS アカウントに1つで足りる共有リソースであり、dev が作った
   ものを `account_id` から決定的に導出した ARN で参照する。
6. DB 認証情報 (`template/{env}/db-credentials` という名前の Secrets Manager シークレット) は
   Terraform (`random_password` + `aws_secretsmanager_secret`) が自動生成する。
   `application-{dev,stg,prod}.yml` の `spring.config.import` がこの名前をそのまま読みに行く
   ので、シークレット名を変える場合は両方揃えて変更すること。
7. Flyway のマイグレーション適用: dev は `:bootstrap` の起動時に自動適用される
   (`spring.flyway.enabled=true`)。stg/prod は起動時に適用しない設計
   (`spring.flyway.enabled=false`) なので、デプロイパイプラインの専用ステップとして
   `flyway migrate` (あるいは同等の CLI/コンテナジョブ) を別途用意する必要がある
   --- **本テンプレートはこの専用ステップ自体を自動化していない** (今のところ
   `.github/workflows/` に Flyway 単体の実行ジョブは無い。必要に応じて追加すること)。

## 5. 継続的な運用で気をつけること

- Arrow/Kotlin/KSP のバージョンを個別に上げない (ADR 0004)。
- detekt を後から追加したくなったら、まず Kotlin 2.3.x 系列との互換性を確認する
  (ADR 0009)。
- `infra/terraform/envs/prod` の削除保護系の設定 (`multi_az`/`deletion_protection`/
  `skip_final_snapshot`) は `terraform.tfvars` ではなく `main.tf` に直接ハードコードして
  あるため、これらを緩めたい場合は `main.tf` 自体を編集する必要がある (意図的なガードレール)。
