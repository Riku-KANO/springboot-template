# 実行可能な example

README のコマンドをコピーし直さなくても主要な経路を試せる、ローカル開発用の入力例です。
いずれも `local` プロファイルでの利用を前提とし、本番用の認証情報は含みません。

## Web API

1. Postgres / LocalStack とアプリケーションを起動する。

   ```bash
   docker compose -f docker/docker-compose.yml up -d
   ./gradlew :bootstrap:bootRun --args='--spring.profiles.active=local'
   ```

2. [`http/orders.http`](http/orders.http) を IntelliJ IDEA の HTTP Client、または `.http` に対応した
   クライアントで開き、必要なリクエストを実行する。

ファイルには health check、注文の作成・一覧・ライフサイクル、キャンセル、累積バリデーション、
日本語の Problem Details 応答、消込バッチ用注文の準備を収録しています。固定 ID を使うため、
同じ作成リクエストを再実行する場合は ID を変更するか、次のコマンドでローカルデータを初期化してください。

```bash
docker compose -f docker/docker-compose.yml down -v
docker compose -f docker/docker-compose.yml up -d
```

## 消込バッチ

[`http/orders.http`](http/orders.http) の「消込 example 用の注文を作成」「決済待ちにする」を順に
実行してから、ヘッダー無しの [`settlement/2026-08-06.csv`](settlement/2026-08-06.csv) を LocalStack
へアップロードします。

```bash
AWS_ACCESS_KEY_ID=test AWS_SECRET_ACCESS_KEY=test \
  aws --endpoint-url http://localhost:4566 --region us-east-1 \
  s3 cp examples/settlement/2026-08-06.csv \
  s3://template-settlements-local/settlements/2026-08-06.csv
```

Web アプリケーションを停止した後、同じ日付をジョブパラメータに指定して実行します。

```bash
./gradlew :bootstrap:bootRun \
  --args='--spring.profiles.active=local,batch --spring.batch.job.name=settlementReconciliationJob settlementDate=2026-08-06'
```

CSV の1行は、次の順で5フィールドです。現在の最小実装はヘッダー行や引用符付きフィールドを
CSV レコードとして扱わないため、example にもヘッダーを入れていません。

```text
orderId,settledAmountMinor,currency,settledAt,providerTransactionId
```
