# ランブック: 消込バッチ (`settlementReconciliationJob`)

## このジョブの全体像 (前提知識)

- ジョブは `settlementDate` (必須) と `taskToken` (Step Functions 経由の場合のみ) の
  2つのジョブパラメータを取る。
- 消込ファイル (`{bucket}/settlements/{yyyy-MM-dd}.csv`) を1行ずつ読み、注文と突き合わせ
  (`reconcile` 関数)、結果を `batch_settlement_results` (成功/不一致/対象外/既済) と
  `batch_settlement_errors` (レコード単位の失敗) に書き分ける。
- ジョブ自体が完走したかどうか (`BatchStatus.COMPLETED` か否か) と、レコード単位の失敗件数
  (`SettlementReport.failedCount`) は別の関心事である。前者が異常終了した場合のみ
  Step Functions へ `SendTaskFailure` が飛ぶ。レコード単位の失敗は `SendTaskSuccess` の
  ペイロード (`SettlementReport`) の一部として運ばれる。

## 手動での再実行

### ローカル/開発環境 (CLI から直接)

```bash
./gradlew :bootstrap:bootRun --args='--spring.profiles.active=<env>,batch --spring.batch.job.name=settlementReconciliationJob settlementDate=<yyyy-MM-dd>'
```

- `taskToken` を渡さなければ `SettlementJobListener.afterJob` はコールバックを一切行わず、
  ログと DB のテーブルだけで結果を確認する運用になる (Step Functions を経由しない手動実行の
  標準的なやり方)。
- 同じ `settlementDate` で再実行しても、`batch_settlement_results`/`batch_settlement_errors`
  への `INSERT` は毎回新しい行として追加される (upsert ではない)。過去の実行結果と混同しないよう、
  `recorded_at` (書き込み時刻) で当該実行分を絞り込むこと。

### 本番相当環境 (ECS RunTask, Pattern B)

Step Functions を経由せず直接 ECS タスクを起動したい場合は、`aws ecs run-task` で
`infra/terraform/modules/ecs-task` が作るタスク定義を直接叩く。`containerOverrides.command`
に `settlement-reconciliation.asl.json` の `StartSettlementBatchTask` ステートと同じ形式の
コマンド (`--spring.profiles.active=<env>,batch --spring.batch.job.name=settlementReconciliationJob
settlementDate=<date>`) を渡す。`taskToken` を渡さなければ通常の手動実行と同じ扱いになる。

## 失敗した Step Functions 実行のリプレイ

1. **まず失敗の種類を切り分ける。** AWS コンソール (あるいは `aws stepfunctions
   describe-execution`) で実行の `status` と `output`/`cause` を確認する。
   - `StartSettlementBatchTask` (Pattern B) / `SendSettlementTaskMessage` (Pattern A) の
     時点で `Catch` に落ちて `SettlementBatchFailed` になっている場合、**ジョブが
     一度も実行されていない** (起動そのものの失敗。ECS の容量不足、IAM 権限不足、
     SQS への送信失敗等)。CloudWatch Logs (`/ecs/<batch task family>` あるいは
     `/aws/vendedlogs/states/<state machine name>`) でエラーメッセージを確認する。
   - `SettlementHadFailures` (Fail) に到達している場合、**ジョブは完走したがレコード単位の
     失敗が1件以上あった** (`failedCount > 0`)。次節「errors テーブルを見る」に進む。
   - `SettlementHasDiscrepancies` (Succeed) の場合、失敗ではなく金額不一致
     (`mismatchCount > 0`)。バッチとしては正常終了であり、人手による消込確認が必要なだけ。
2. **リプレイする。** このステートマシンは冪等な設計を前提にしていない
   (同じ `settlementDate` を再実行すると `batch_settlement_results`/`batch_settlement_errors`
   に重複行が増える)。起動そのものが失敗したケース (1.の1つ目) は単純に同じ `input`
   (`{"settlementDate": "..."}`) で `start-execution` をやり直してよい。レコード単位の
   失敗があったケース (1.の2つ目) は、まず原因 (下記) を特定・修正してから再実行すること。
   原因を直さずに再実行すると同じ失敗を繰り返すだけでなく、結果テーブルに重複した
   実行履歴が積み上がる。

## 記録が errors テーブルに載ったときに見るところ

`batch_settlement_errors` (`adapter-persistence/.../db/migration/V4__create_settlement_errors.sql`
はドメイン側の正データ用、`V5__create_batch_settlement_tables.sql` の
`batch_settlement_errors` はこのバッチジョブの実行監査ログ --- 混同しないこと) の
`error_type` カラムで原因を絞り込む。

| `error_type` | 対応する `SettlementError` | 典型的な原因 | 見るべき場所 |
|---|---|---|---|
| `MALFORMED_RECORD` | `SettlementError.MalformedRecord` | 消込ファイルの1行が5フィールド (`orderId,settledAmountMinor,currency,settledAt,providerTransactionId`) になっていない、日時/金額のフォーマットが不正 | 該当日の消込ファイルそのもの (決済プロバイダ側の出力形式の変更を疑う) |
| `UNKNOWN_ORDER` | `SettlementError.UnknownOrder` | 消込ファイルが参照する `orderId` が `orders` テーブルに存在しない | 決済プロバイダ側の orderId と自社の `orderId` の対応関係、あるいは注文が別環境向けのものが紛れ込んでいないか |
| `INFRASTRUCTURE_FAILURE` | `SettlementError.InfrastructureFailure` | S3 からのファイル取得失敗、DB 接続の一時的な障害 | `S3SettlementFileAdapter` 周りのログ (バケット名/キーが `settlement.s3.*` の設定と一致しているか)、RDS の状態 |

`batch_settlement_results` の `outcome` カラムは失敗ではなく、消込の判定結果を表す。
`AMOUNT_MISMATCH` (金額不一致) の行は `expected_amount_minor`/`actual_amount_minor` の
両方が埋まっているので、決済プロバイダ側の請求額と自社の注文合計のどちらが正しいかを
突き合わせて調査する。`ORDER_NOT_SETTLEABLE` は注文がそもそも消込可能な状態 (`Draft`/
`Cancelled`) ではなかったケースで、多くの場合は消込ファイル側の対象期間の設定ミスを疑う。

## タイムアウトしたまま終わらない実行を見つけたら

Step Functions の実行がいつまでも `RUNNING` のままの場合、まず疑うのは
`SettlementJobListener.afterJob` の `SendTaskSuccess`/`SendTaskFailure` 呼び出しが失敗している
ケース (`states:SendTaskSuccess`/`SendTaskFailure` の IAM 権限不足等)。`taskToken` が
job parameter として渡っていること自体は前提として確認済みでも、コールバック自体が
IAM 権限や AWS SDK のネットワークエラーで失敗すると、ジョブは正常に完了しているのに
ステートマシンだけが `.waitForTaskToken` のタイムアウト (ASL の `TimeoutSeconds`, この
テンプレートの既定は 3600 秒) まで待ち続ける。バッチ側のログ
(`SettlementJobListener` の `"failed to notify Step Functions of job outcome"`) を
必ず確認すること。
