# Changelog

このプロジェクトはテンプレートリポジトリであり、通常のアプリケーションのようなユーザー向け
リリースはありません。ここでは「このテンプレート自体」に何がどの段階で揃ったかを記録します。
まだ v1.0.0 のようなタグは切っていません (`Unreleased` として蓄積中)。

フォーマットは [Keep a Changelog](https://keepachangelog.com/) に緩く準拠します。

## [Unreleased]

### Added

- **基盤・ドメイン**: Gradle マルチモジュール構成 (`:domain`/`:application`/`:adapter-persistence`/
  `:adapter-web`/`:adapter-messaging`/`:batch`/`:bootstrap`)、build-logic による precompiled
  script plugin。EC 注文の集約 (`Order`, 8状態の `OrderStatus`) と日次消込
  (`Reconciliation`/`SettlementRecord`)。2軸のドメインエラー ADT
  (`DomainError`/`ValidationError`/`NotFoundError`/`ConflictError`/`InfrastructureError` x
  `OrderError`/`SettlementError`/`ValueError`)。
- **Web API**: WebFlux + Kotlin コルーチンによる `OrderController` (作成/参照/確定/決済/
  出荷準備開始/出荷/配達/キャンセル/返金)。RFC 9457 `ProblemDetail` へのエラーマッピング。
  JWT リソースサーバーとしての Spring Security 設定。springdoc-openapi による Swagger UI。
  リクエスト ID の MDC 伝搬 (`CoWebFilter` + `MDCContext`)。
- **汎用APIパターン**: ID cursorによる注文一覧、`version` 列を使う楽観ロック、
  `orders.read`/`orders.write` OAuth2 scope認可、日英の競合エラー応答。
- **永続化**: R2DBC による注文/消込レコードの永続化、Flyway マイグレーション、
  `TxRunner`/`R2dbcTxRunner` によるトランザクション境界 (`Either.Left` の明示的ロールバック)。
- **バッチ**: Spring Batch 6 による `settlementReconciliationJob` (chunk 指向、
  skip-and-report によるレコード単位のエラーハンドリング)。JDBC + R2DBC のデュアル
  DataSource/ConnectionFactory 構成。
- **メッセージング/AWS 連携**: S3 からの消込ファイル読み込み、SQS 経由のバッチ起動トリガー、
  Step Functions の `.waitForTaskToken` パターンに対する `SendTaskSuccess`/`SendTaskFailure`
  コールバック (`SfnTaskCallbackAdapter`)。`arrow-resilience` によるリトライ + サーキットブレーカー
  (`ResilientPaymentGatewayAdapter`)。
- **インフラ (Terraform)**: `infra/terraform/modules/{network,rds-postgres,ecr,ecs-cluster,
  ecs-service,ecs-task,s3-bucket,sqs-queue,sfn-state-machine,iam}` の再利用可能モジュール群と、
  `infra/terraform/envs/{dev,stg,prod}` の独立したルートモジュール (環境ごとに意味のある
  サイジング/HA/削除保護の差異を持つ)。Step Functions ASL 定義2種
  (`ecs:runTask.waitForTaskToken` の Pattern B、`sqs:sendMessage.waitForTaskToken` の Pattern A)。
- **公開・監視基盤**: ALB、ACM HTTPS redirect、Route 53 alias、ECS target health check、
  CloudWatch alarms (ALB/ECS/RDS/SQS DLQ) とSNS通知。RDS接続情報をECSへ実配線。
- **可観測性**: Prometheus registry、Micrometer Tracing + OpenTelemetry exporter、
  liveness/readiness probe、ECS構造化ログ。
- **CI/CD**: `.github/workflows/ci.yml` (ビルド・テスト・ktlint/spotless、Testcontainers込み)、
  `docker-build-push.yml` (OIDC、SHA固定イメージ、Flyway ECS task、ECS deploy、smoke test、
  Cosign署名、CycloneDX SBOM、脆弱性scan)、`security.yml` (CodeQL/dependency review)、Dependabot、`terraform-plan.yml`
  (`infra/**` を変更する PR での fmt/init/validate/plan)。
- **ドキュメント**: `README.md`, `docs/architecture.md`, `docs/arrow-style-guide.md`,
  `docs/testing-strategy.md`, `docs/local-development.md`, `docs/how-to-use-this-template.md`,
  `docs/runbooks/settlement-batch.md`, ADR 9本 (`docs/adr/0001`〜`0009`)。

### Fixed

- **注文ライフサイクルの断絶**: `Order.create()` が返す初期状態 `Draft` から、`PayOrder`
  (前提 `PendingPayment`) や `ShipOrder` (前提 `Fulfilling`) が要求する状態へ到達する手段が
  無く、`POST /orders/{id}/pay` と `POST /orders/{id}/ship` が作成直後の注文に対して常に
  409 Conflict (`order-invalid-transition`) を返していた。`SubmitOrderForPayment`
  (`Draft -> PendingPayment`)・`StartFulfillment` (`Paid -> Fulfilling`)・`DeliverOrder`
  (`Shipped -> Delivered`)・`RefundOrder` (`{Paid, Delivered} -> Refunded`) の4ユースケースを
  `:application` に、対応するエンドポイント (`POST /orders/{id}/submit`・`/start-fulfillment`・
  `/deliver`・`/refund`) を `:adapter-web` に追加し、`:bootstrap` の `UseCaseBeans.kt` に
  Bean 登録した。これにより `Draft` から `Shipped`/`Delivered`/`Cancelled`/`Refunded` までの
  7つの domain 遷移すべてに実際の HTTP エンドポイント経由で到達できるようになった。
  テスト数は128件から150件に増加。

### Known limitations

- Step Functions の Pattern B (`ecs:runTask.waitForTaskToken`) は LocalStack Community が
  ECS をサポートしないため、ローカルでは検証できない。
