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
   `backend.tf` のbucket/table名と `terraform_state_bucket_name`/
   `terraform_lock_table_name` は必ず同じ値にする。別AWSアカウントの共通bucketを使う場合は、
   各環境の `github_actions_plan_role_arn` にstate読取を許すbucket policyも必要になる。
2. **`terraform.tfvars` を実際の値に書き換える** (`account_id`, `github_repository`,
   `settlement_bucket_name`, `alarm_email` 等。prod ではさらに `api_certificate_arn`,
   `api_hosted_zone_id`, `api_domain_name` が必須。プレースホルダのままでは apply できない、あるいは
   意図しないアカウント/バケットに向いてしまう)。
3. **dev から順に、人手の AWS 認証情報 (管理者権限、または十分な権限を持つロール) で
   `terraform apply` する。** このとき `create_github_oidc_provider = true`
   (dev の `terraform.tfvars` の既定値) により、GitHub Actions 用の OIDC プロバイダと
   ロールも同時に作られる。
4. **OIDC ブートストラップ (鶏と卵の解消)**: GitHub の `dev`/`stg`/`prod` Environment を作り、
   各 Environment の Variables に以下を登録する。値は同じ環境の Terraform output と
   `aws_region` から得られる。Environmentのdeployment branch/tag ruleもdev=`main`、
   stg=`main`とrelease tag、prod=`main`に絞り、prodにはrequired reviewersを設定することを推奨する。
   Environmentを使うjobのOIDC `sub` にはrefが含まれないため、このGitHub側ルールも認可境界の一部になる。

   | Variable | 値 |
   |---|---|
   | `AWS_GITHUB_ACTIONS_ROLE_ARN` | `github_actions_role_arn` |
   | `AWS_TERRAFORM_PLAN_ROLE_ARN` | `github_actions_plan_role_arn` |
   | `AWS_REGION` | Terraform の `aws_region` |
   | `ECR_REPOSITORY_URL` | `ecr_repository_url` |
   | `ECS_CLUSTER` | `ecs_cluster_name` |
   | `ECS_API_SERVICE` | `ecs_api_service_name` |
   | `ECS_BATCH_TASK_FAMILY` | `ecs_batch_task_family` |
   | `API_ENDPOINT` | `api_endpoint` |

   Terraform planもAWS refresh込みで実行する場合は、全Environmentの登録後にRepository variable
   `TERRAFORM_PLAN_ENABLED=true` を追加する。初回apply前はplan role自体が存在しないため、
   このflagを設定せずfmt/validateだけを動かす。

   ここまでやって初めて `.github/workflows/docker-build-push.yml` が動くようになる
   (このワークフロー自身がまだ存在しないロールを使って自分を bootstrap することはできない、
   という制約は最初から織り込み済み)。
5. **stg/prod は `create_github_oidc_provider = false` のまま** (既定値) apply する。
   OIDC プロバイダは AWS アカウントに1つで足りる共有リソースであり、dev が作った
   ものを `account_id` から決定的に導出した ARN で参照する。
6. DB 認証情報 (`template/{env}/db-credentials`) は Terraform が自動生成し、JDBC/R2DBC の
   URL・username・password は ECS API/Batch task definition に環境変数と secret referenceで
   注入される。`application-{env}.yml` のホスト名はフォールバック例であり、ECSでは使われない。
7. `main` へのpushはdevへ自動デプロイする。stg/prodは `Build & Deploy` を手動実行し、対象
   Environmentを選ぶ。ワークフローはSHA固定イメージのbuild/push/署名、SBOM生成とattestation、
   脆弱性スキャン、`<env>,migration` ECSワンショットタスク、API task definition更新、service安定待ち、
   readiness smoke testの順で進む。AWS環境では起動時Flywayを無効にし、このmigration taskを
   唯一のスキーマ適用経路にしている (localだけは起動時に適用する)。

## 5. 継続的な運用で気をつけること

- Arrow/Kotlin/KSP のバージョンを個別に上げない (ADR 0004)。
- detekt を後から追加したくなったら、まず Kotlin 2.3.x 系列との互換性を確認する
  (ADR 0009)。
- `infra/terraform/envs/prod` の削除保護系の設定 (`multi_az`/`deletion_protection`/
  `skip_final_snapshot`) は `terraform.tfvars` ではなく `main.tf` に直接ハードコードして
  あるため、これらを緩めたい場合は `main.tf` 自体を編集する必要がある (意図的なガードレール)。
