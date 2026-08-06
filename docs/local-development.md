# ローカル開発環境

すべてのコマンドはリポジトリのルートから実行する前提。Windows (Git Bash/PowerShell) と
Docker Desktop があれば、実 AWS アカウントに一切触れずにこのテンプレートの主要な機能
(Web API, 消込バッチ, Step Functions 連携) を最後まで動かせる。

## 1. Postgres + LocalStack を起動する

```bash
docker compose -f docker/docker-compose.yml up -d
```

2つのコンテナが立ち上がる。

- `template-postgres` (postgres:17-alpine, `localhost:5432`, db/user/password はすべて `template`)
- `template-localstack` (localstack/localstack:4.9, `localhost:4566`)

`docker/docker-compose.yml` の LocalStack の `SERVICES` 環境変数は
`s3,sqs,stepfunctions,sts,iam,ssm,secretsmanager` である点に注意。**`sts` と `iam` を
省略すると `Service 'sts' is not enabled` で Step Functions の
`aws-sdk:sqs:sendMessage.waitForTaskToken` 統合が実行開始直後に FAILED になる**
(内部的な認証情報解決が sts に依存するため)。`s3,sqs,stepfunctions` の3つだけで足りると
誤解しやすいので明記しておく。

起動完了は `docker compose -f docker/docker-compose.yml ps` で両コンテナが `healthy` に
なることで確認できる。`docker/localstack-init/init-aws.sh` が LocalStack の全サービス起動後に
自動実行され、S3 バケット (`template-settlements-local`)・SQS キュー
(`template-settlement-tasks-local`)・Step Functions ステートマシン
(`template-settlement-reconciliation-local`) を作る。

## 2. アプリケーションを起動する (Web API)

```bash
./gradlew :bootstrap:bootRun --args='--spring.profiles.active=local'
```

`local` プロファイルは `template.security.permit-all=true` を設定しており、JWT 無しで
全エンドポイントを叩ける (`bootstrap/.../config/LocalSecurityConfig.kt` 参照)。起動すると
Flyway が6件のマイグレーション (Batch のメタデータ含む) を適用し、Netty が `8080` 番ポートで
待ち受ける。

### 動作確認 (実際に検証済みのコマンドと応答)

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
      "recipientName": "Taro Yamada",
      "postalCode": "100-0001",
      "prefecture": "Tokyo",
      "city": "Chiyoda",
      "addressLine1": "1-1-1 Marunouchi"
    }
  }'
# HTTP/1.1 201 Created
# {"id":"order-0001","customerId":"customer-0001","status":"DRAFT", ... }

curl -s http://localhost:8080/orders/order-0001
# 上と同じ内容が返る (GET)

curl -s -X POST http://localhost:8080/orders/order-0001/submit
# {"id":"order-0001", ..., "status":"PENDING_PAYMENT", ...}

curl -s -X POST http://localhost:8080/orders/order-0001/pay
# {"id":"order-0001", ..., "status":"PAID", ...}

curl -s -X POST http://localhost:8080/orders/order-0001/start-fulfillment
# {"id":"order-0001", ..., "status":"FULFILLING", ...}

curl -s -X POST http://localhost:8080/orders/order-0001/ship \
  -H "Content-Type: application/json" -d '{"trackingNumber":"TRACK-0001"}'
# {"id":"order-0001", ..., "status":"SHIPPED", ...}

curl -s -X POST http://localhost:8080/orders/order-0001/deliver
# {"id":"order-0001", ..., "status":"DELIVERED", ...}

curl -s http://localhost:8080/orders/order-0001
# {"id":"order-0001", ..., "status":"DELIVERED", ...}
```

`submit` (`Draft -> PendingPayment`) → `pay` (`PendingPayment -> Paid`) → `start-fulfillment`
(`Paid -> Fulfilling`) → `ship` (`Fulfilling -> Shipped`) → `deliver` (`Shipped -> Delivered`)
という順に、注文作成直後の `Draft` から `OrderTransitions.kt` の7つの domain 遷移のうち5つを
辿ったことになる。残り2つ (`cancel`/`refund`) は以下で確認する。

返金 (`{Paid, Delivered} -> Refunded`) は、配達完了後の返品対応としても呼べる。返金額は
`amountMinor` (最小通貨単位) + `currency` (ISO-4217) のペアで指定する
(`RefundOrderRequest` 参照)。

```bash
curl -s -X POST http://localhost:8080/orders/order-0001/refund \
  -H "Content-Type: application/json" -d '{"amountMinor":3000,"currency":"JPY"}'
# {"id":"order-0001", ..., "status":"REFUNDED", ...}

# Refunded は終端状態なので、同じ注文への2回目の refund はエラーマッピングの動作確認になる
curl -s -i -X POST http://localhost:8080/orders/order-0001/refund \
  -H "Content-Type: application/json" -d '{"amountMinor":3000,"currency":"JPY"}'
# HTTP/1.1 409 Conflict (application/problem+json)
# type: https://errors.example.com/problems/order-invalid-transition
```

キャンセル (`{Draft, PendingPayment, Paid} -> Cancelled`) は、出荷準備 (`Fulfilling`) に
着手する前の注文でのみ呼べる業務ルールになっている。上の注文はすでに `Fulfilling` を
通過済みなので、キャンセルのデモには別の注文を新規作成する。

```bash
curl -s -i -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "order-0002",
    "customerId": "customer-0001",
    "currency": "JPY",
    "lines": [{ "sku": "SKU-0001", "quantity": 1, "unitPriceMinor": 1500 }],
    "address": {
      "recipientName": "Taro Yamada",
      "postalCode": "100-0001",
      "prefecture": "Tokyo",
      "city": "Chiyoda",
      "addressLine1": "1-1-1 Marunouchi"
    }
  }'
# HTTP/1.1 201 Created

curl -s -X POST http://localhost:8080/orders/order-0002/cancel \
  -H "Content-Type: application/json" -d '{"reason":"customer requested"}'
# {"id":"order-0002", ..., "status":"CANCELLED", ...}
```

累積バリデーションのデモ (orderId/customerId/明細のすべてが不正な場合):

```bash
curl -s -i -X POST http://localhost:8080/orders \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "", "customerId": "", "currency": "JPY",
    "lines": [{ "sku": "bad sku!!", "quantity": -1, "unitPriceMinor": 1500 }],
    "address": { "recipientName": "x", "postalCode": "x", "prefecture": "x", "city": "x", "addressLine1": "x" }
  }'
# HTTP/1.1 400 Bad Request (application/problem+json)
# "errors": [
#   "OrderId must not be blank",
#   "CustomerId must not be blank",
#   "'bad sku!!' is not a valid SKU (expected [A-Z0-9-]{1,32})",
#   "Quantity must be positive, but was -1"
# ]
```

Swagger UI は `http://localhost:8080/swagger-ui.html` (302 で `/swagger-ui/index.html` へ
リダイレクトされる)、OpenAPI ドキュメントは `http://localhost:8080/v3/api-docs`。

## 3. 消込バッチをワンショットで実行する

S3 に消込ファイルを置いてから、`batch` プロファイルでジョブを起動する。

```bash
# 消込ファイルを LocalStack の S3 に置く (1行: orderId,settledAmountMinor,currency,settledAt,providerTransactionId)
echo "order-0001,3000,JPY,2026-08-06T01:00:00Z,txn-0001" > /tmp/2026-08-06.csv
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url http://localhost:4566 --region us-east-1 \
  s3 cp /tmp/2026-08-06.csv s3://template-settlements-local/settlements/2026-08-06.csv

# Web サーバーを起動していたら一度落としてからバッチを実行する (spring.main.web-application-type=none)
./gradlew :bootstrap:bootRun --args='--spring.profiles.active=local,batch --spring.batch.job.name=settlementReconciliationJob settlementDate=2026-08-06'
```

`settlementDate=2026-08-06` は **非オプションの引数** (`--` を付けない) であり、
`JobLauncherApplicationRunner` が `String` 型の `JobParameter` として登録したものを、
`@StepScope` の `settlementRecordItemReader` が SpEL (`#{jobParameters['settlementDate']}`) +
`ApplicationConversionService` の自動変換で `LocalDate` として受け取る。型トークン付きの
構文 (`settlementDate=2026-08-06,date`) は不要 --- 実機で確認済み。

ジョブはワンショットで完了し、プロセスは自然終了する
(`spring.cloud.aws.sqs.listener.auto-startup=false` を `batch` プロファイルが設定しているため。
これが無いと `SettlementTaskListener` の SQS リスナーの非デーモンスレッドが原因で
JVM が終了しなくなる)。結果は Postgres で確認できる。

```bash
docker exec template-postgres psql -U template -d template \
  -c "SELECT order_id, outcome FROM batch_settlement_results;"
docker exec template-postgres psql -U template -d template \
  -c "SELECT * FROM batch_settlement_errors;"
```

## 4. Step Functions 経由でバッチを起動する (Pattern A, ローカルで完結)

Web API (`local` プロファイル、`batch` を付けない) を起動した状態で、LocalStack 上の
ステートマシンを直接実行する。

```bash
export AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test

aws --endpoint-url http://localhost:4566 --region us-east-1 stepfunctions start-execution \
  --state-machine-arn arn:aws:states:us-east-1:000000000000:stateMachine:template-settlement-reconciliation-local \
  --input '{"settlementDate":"2026-08-06"}'

# 数秒待ってから実行結果を確認する
aws --endpoint-url http://localhost:4566 --region us-east-1 stepfunctions describe-execution \
  --execution-arn <上のコマンドが返した executionArn>
```

実機で検証したところ、`"status": "SUCCEEDED"` となり、`output` に実際の `SettlementReport`
の JSON (`processedAt`/`matchedCount`/`mismatchCount`/`alreadySettledCount`/
`notSettleableCount`/`failedCount`/`totalCount`/`verdict`) がそのまま入って返ってくる。
`verdict` は `SettlementVerdict` という sealed interface で、JSON 上は型タグを持たず
中身のフィールドだけで表現される (`MatchedAll` は `{}`、`HasDiscrepancies` は
`{"mismatchCount": N}`、`Failed` は `{"failedCount": N}`)。

このフローの実体は `docker/localstack-init/init-aws.sh` がインラインで作る、最小構成の
ステートマシン定義であり、`infra/terraform/statemachine/settlement-reconciliation-sqs.asl.json`
(通知・リトライ・Choice 分岐込みの本番相当版) とは別物 (後者は Terraform 経由でしか
デプロイしない)。どちらも同じ `arn:aws:states:::sqs:sendMessage.waitForTaskToken` 統合を使う。

## 5. Pattern B (ECS RunTask) はローカルで検証できない

`infra/terraform/statemachine/settlement-reconciliation.asl.json` (`ecs:runTask.waitForTaskToken`)
は本番運用のデフォルトだが、**LocalStack Community は ECS をサポートしていない (Pro 版限定)**。
そのためこのステートマシンをローカルの docker-compose 環境で実行する手段は無い。
これは制約であって仕様のバグではなく、正直にそう受け止めること。動作確認は以下のいずれかで
代替する。

- Pattern A (上記 4.) でステートマシンの分岐ロジック (`EvaluateSettlementVerdict` の Choice、
  SNS 通知) 自体は等価に検証できる (ASL 定義はほぼ共通のロジックを異なる起動方法で
  ラップしているだけ)。
- 実際の ECS RunTask 起動は、`infra/terraform/envs/dev` を実際の AWS 開発アカウントに
  apply した上で dev 環境に対して実行して確認する。

## 後片付け

```bash
docker compose -f docker/docker-compose.yml down -v   # コンテナとボリュームを削除
./gradlew --stop                                       # Gradle デーモンを止める
```
