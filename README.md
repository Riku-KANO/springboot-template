# springboot-template

Spring Boot + Kotlin + Arrow による、関数型アプローチのバックエンドテンプレートです。
「EC 注文の管理」と「決済プロバイダの日次消込バッチ」という具体的なドメインをサンプルとして
実装しつつ、マルチモジュール構成・エラーハンドリング・非同期処理・AWS 連携・Infrastructure as
Code・CI/CD までを一通り揃えています。

このテンプレートは実際にローカルで docker compose + LocalStack を使って最後まで動作確認済みです
(Web API のリクエスト、消込バッチのワンショット実行、Step Functions 経由のバッチ起動)。
「動くことを確認していない手順」はこのドキュメント群には書いていません。

## このテンプレートで実演していること

- WebFlux + Kotlin コルーチン + Arrow の `either { }` DSL を組み合わせた、命令的に読める
  非同期エラーハンドリング
- 2軸のドメインエラー ADT (バウンデッドコンテキスト軸 x カテゴリ軸) による、モジュールを跨いだ
  網羅性検査
- 累積バリデーション (`EitherNel` + `zipOrAccumulate`/`mapOrAccumulate`) とフェイルファストの
  使い分け
- Gradle のモジュール分割による、実行時チェッカー無しでのヘキサゴナルアーキテクチャの強制
- Spring Batch 6 + JDBC/R2DBC デュアル接続構成
- Step Functions の `.waitForTaskToken` パターン (SQS 版・ECS RunTask 版の両方)
- cursor pagination、楽観ロック、OAuth2 scope 認可を含む実運用向け API パターン
- Prometheus metrics + OpenTelemetry tracing、構造化ログ、readiness/liveness probe
- Terraform による ALB/HTTPS/DNS/監視を含むマルチ環境 (dev/stg/prod) の Infrastructure as Code
- SHA 固定イメージ、Flyway migration task、SBOM/署名/脆弱性検査を含むデプロイパイプライン

## スタック

| 領域 | 技術 | バージョン |
|---|---|---|
| 言語 | Kotlin | 2.3.21 |
| フレームワーク | Spring Boot | 4.1.0 (Spring Framework 7.0.8) |
| JVM | Java (baseline) | 17 |
| ビルド | Gradle | 9.4.0 |
| 関数型プログラミング | Arrow | 2.2.2 (2.2.3 は禁止。ADR 0004 参照) |
| バッチ | Spring Batch | 6.0.4 |
| JSON | Jackson | 3 (`tools.jackson.*`) |
| マイグレーション | Flyway | 12.4.0 |
| 統合テスト | Testcontainers | 2.0.5 |
| テストランナー | JUnit | 6 (Kotest はライブラリとしてのみ。ADR 0005 参照) |
| AWS SDK | AWS SDK v2 | 2.51.0 |
| AWS Spring 統合 | Spring Cloud AWS | 4.1.0 |
| API ドキュメント | springdoc-openapi | 3.1.0 |
| DB ドライバ (アプリ経路) | R2DBC PostgreSQL | 1.1.2.RELEASE |
| 静的解析 | ktlint + spotless (detekt は不採用。ADR 0009 参照) | - |

## モジュール構成

```
:domain              Spring 依存ゼロ。集約・値オブジェクト・エラー ADT。
:application         Spring 依存ゼロ。ユースケース + ポート interface。
:adapter-persistence R2DBC / Flyway マイグレーション。
:adapter-web         WebFlux コントローラ / Security / OpenAPI。
:adapter-messaging   SQS / S3 / Step Functions 連携。
:batch               Spring Batch のジョブ/ステップ定義。
:bootstrap           composition root。bootJar を生成する唯一のモジュール。
```

`:domain`/`:application` に Spring への依存を持ち込もうとすると、lint 警告ではなく
**コンパイルエラー** になります (build-logic の規約がクラスパスにその依存を追加しないため)。
詳しくは [`docs/architecture.md`](docs/architecture.md) を参照してください。

## クイックスタート

前提: JDK 17、Docker / Docker Compose。

### 1. Postgres + LocalStack を起動する

```bash
docker compose -f docker/docker-compose.yml up -d
```

`docker/localstack-init/init-aws.sh` が自動実行され、S3 バケット・SQS キュー・Step Functions
ステートマシンがローカルに用意されます。

### 2. アプリケーションを起動する

```bash
./gradlew :bootstrap:bootRun --args='--spring.profiles.active=local'
```

### 3. 動作確認 (実際に検証済みのコマンド)

```bash
curl -s http://localhost:8080/actuator/health
# {"groups":["liveness","readiness"],"status":"UP"}

curl -s -i -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "order-0001",
    "customerId": "customer-0001",
    "currency": "JPY",
    "lines": [{ "sku": "SKU-0001", "quantity": 2, "unitPriceMinor": 1500 }],
    "address": {
      "recipientName": "Taro Yamada", "postalCode": "100-0001",
      "prefecture": "Tokyo", "city": "Chiyoda", "addressLine1": "1-1-1 Marunouchi"
    }
  }'
# HTTP/1.1 201 Created

curl -s http://localhost:8080/orders/order-0001

# ID cursorで一覧取得 (limit: 1..100)
curl -s 'http://localhost:8080/orders?limit=20'

# Prometheus形式のメトリクス
curl -s http://localhost:8080/actuator/prometheus
```

Swagger UI: `http://localhost:8080/swagger-ui.html`。より詳しい手順 (累積バリデーションの
デモ、注文ライフサイクル一巡 [作成 → 確定 → 決済 → 出荷準備 → 出荷 → 配達 → 返金] のデモ、
キャンセルのデモ) は [`docs/local-development.md`](docs/local-development.md) を参照してください。
IDE や `.http` 対応クライアントから順に実行できるリクエスト集と、それに対応する消込 CSV は
[`examples/`](examples/) にあります。

### 4. 消込バッチをワンショットで実行する

```bash
./gradlew :bootstrap:bootRun --args='--spring.profiles.active=local,batch --spring.batch.job.name=settlementReconciliationJob settlementDate=2026-08-06'
```

`settlementDate=...` は非オプションのジョブパラメータです (`--` を付けない)。詳細・S3への
消込ファイルの置き方は [`docs/local-development.md`](docs/local-development.md) を参照。

### 5. Step Functions 経由でバッチを起動する (Pattern A, ローカルで完結)

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test
aws --endpoint-url http://localhost:4566 --region us-east-1 stepfunctions start-execution \
  --state-machine-arn arn:aws:states:us-east-1:000000000000:stateMachine:template-settlement-reconciliation-local \
  --input '{"settlementDate":"2026-08-06"}'
```

実行が `SUCCEEDED` になり、`output` に実際の `SettlementReport` の JSON が入って返ってきます
(実機で確認済み)。本番運用のデフォルトである Pattern B (`ecs:runTask.waitForTaskToken`) は
LocalStack Community が ECS を未サポートのためローカルでは検証できません --- 詳細は
[`docs/local-development.md`](docs/local-development.md) に正直に書いています。

## ビルド・テスト

```bash
./gradlew build                     # 全モジュールのビルド + テスト (160件, 0 failures)
./gradlew ktlintCheck spotlessCheck # 静的解析
```

## インフラ (Terraform)

```
infra/terraform/
├── modules/          network, alb, rds-postgres, ecr, ecs-cluster, ecs-service, ecs-task,
│                      s3-bucket, sqs-queue, sfn-state-machine, iam, monitoring
├── envs/{dev,stg,prod}/  独立したルートモジュール (S3 + DynamoDB リモートステート)
└── statemachine/     Step Functions の ASL 定義 (Pattern A/B の両方)
```

```bash
cd infra/terraform/envs/dev
terraform fmt -check -recursive
terraform init -backend=false
terraform validate
```

初回デプロイの手順 (リモートステートの準備、GitHub Actions OIDC のブートストラップ等) は
[`docs/how-to-use-this-template.md`](docs/how-to-use-this-template.md) を参照してください。

## ドキュメント一覧

| ドキュメント | 内容 |
|---|---|
| [`docs/architecture.md`](docs/architecture.md) | モジュール構成、依存ルールの強制方法、リクエスト/バッチフロー図 |
| [`docs/arrow-style-guide.md`](docs/arrow-style-guide.md) | Either/EitherNel の使い分け、`.bindNel()` の罠、optics |
| [`docs/error-handling-and-i18n.md`](docs/error-handling-and-i18n.md) | 安定エラーコード、安全な公開文言、Accept-Language による日英対応 |
| [`docs/testing-strategy.md`](docs/testing-strategy.md) | JUnit 6 と Kotest の使い分け、Testcontainers |
| [`docs/local-development.md`](docs/local-development.md) | ローカル環境の構築・動作確認手順 |
| [`docs/how-to-use-this-template.md`](docs/how-to-use-this-template.md) | パッケージ名の変更、不要なサンプルの削除、初回デプロイ |
| [`docs/adr/`](docs/adr/) | 9本の Architecture Decision Record |
| [`docs/runbooks/settlement-batch.md`](docs/runbooks/settlement-batch.md) | 消込バッチの手動再実行、失敗時の調査手順 |
| [`examples/`](examples/) | 実行可能な HTTP リクエスト集と消込 CSV |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | このテンプレート自体への contribution ガイド |
| [`CHANGELOG.md`](CHANGELOG.md) | このテンプレートの変更履歴・既知の制約 |

## 既知の制約

- Step Functions の Pattern B (ECS RunTask) はローカルで検証できません (LocalStack
  Community の制約)。
