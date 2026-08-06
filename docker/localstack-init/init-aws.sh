#!/usr/bin/env bash
# ============================================================================
# LocalStack 起動時の初期化スクリプト (/etc/localstack/init/ready.d にマウントされ、
# 全サービスが READY になった後に LocalStack 自身が自動実行する)。
#
# ここで作るもの:
#   - 消込ファイル用の S3 バケット (S3SettlementFileAdapter / settlement.s3.bucket-name)
#   - バッチ起動要求用の SQS キュー (SettlementTaskListener / settlement.sqs.queue-name)
#   - "aws-sdk:sqs:sendMessage.waitForTaskToken" 統合を使う Step Functions ステートマシン
#     (このテンプレートの Step Functions 連携がローカルで動くことを実演する最小構成。
#     実運用の ASL は infra/terraform 側 (別チャンクの守備範囲) が持つため、ここでは
#     ローカル動作確認用に自己完結させる)
#   - local プロファイルが読みにいく想定の SSM パラメータ / Secrets Manager シークレットの
#     ダミーエントリ (実際には application-local.yml は spring.config.import で
#     SSM/Secrets Manager を一切参照しない設計だが、dev/stg/prod と同じ経路がローカルでも
#     動作確認できるように最小限のエントリだけ用意しておく)
#
# 値 (バケット名・キュー名等) は bootstrap/src/main/resources/application-local.yml の
# settlement.s3.bucket-name / settlement.sqs.queue-name と必ず一致させること。
# ============================================================================
set -euo pipefail

REGION="us-east-1"
BUCKET="template-settlements-local"
QUEUE_NAME="template-settlement-tasks-local"
STATE_MACHINE_NAME="template-settlement-reconciliation-local"

echo "[init-aws] creating S3 bucket: $BUCKET"
awslocal s3 mb "s3://$BUCKET" --region "$REGION"

echo "[init-aws] creating SQS queue: $QUEUE_NAME"
QUEUE_URL=$(awslocal sqs create-queue --queue-name "$QUEUE_NAME" --region "$REGION" --query 'QueueUrl' --output text)
echo "[init-aws] queue URL: $QUEUE_URL"

# aws-sdk:sqs:sendMessage.waitForTaskToken: Step Functions がタスクトークンを発行して SQS に
# メッセージを送り、ワーカー (:adapter-messaging の SettlementTaskListener) がバッチ完了後に
# SendTaskSuccess/SendTaskFailure を呼ぶまで実行を一時停止する。MessageBody に settlementDate と
# taskToken を JSON として詰める (SettlementTaskListener.kt の SettlementTaskMessage と同じ形)。
STATE_MACHINE_DEFINITION=$(cat <<JSON
{
  "Comment": "settlement reconciliation batch trigger (local, via LocalStack)",
  "StartAt": "StartSettlementBatch",
  "States": {
    "StartSettlementBatch": {
      "Type": "Task",
      "Resource": "arn:aws:states:::aws-sdk:sqs:sendMessage.waitForTaskToken",
      "Parameters": {
        "QueueUrl": "$QUEUE_URL",
        "MessageBody": {
          "settlementDate.\$": "\$.settlementDate",
          "taskToken.\$": "\$\$.Task.Token"
        }
      },
      "End": true
    }
  }
}
JSON
)

echo "[init-aws] creating state machine: $STATE_MACHINE_NAME"
awslocal stepfunctions create-state-machine \
  --name "$STATE_MACHINE_NAME" \
  --definition "$STATE_MACHINE_DEFINITION" \
  --role-arn "arn:aws:iam::000000000000:role/service-role/StatesExecutionRole" \
  --region "$REGION"

# local プロファイルは実際には SSM/Secrets Manager を参照しないが (spring.config.import は
# dev/stg/prod だけで使う)、dev 以降と同じ経路をローカルでも触れるようにダミーエントリだけ用意する。
echo "[init-aws] seeding SSM parameter / Secrets Manager secret (placeholder, not consumed by local profile)"
awslocal ssm put-parameter --name "/template/local/placeholder" --value "placeholder" --type String --region "$REGION" --overwrite
awslocal secretsmanager create-secret --name "template/local/db-credentials" \
  --secret-string '{"username":"template","password":"template"}' --region "$REGION" >/dev/null 2>&1 || true

echo "[init-aws] done: bucket=$BUCKET queue=$QUEUE_NAME state-machine=$STATE_MACHINE_NAME"
