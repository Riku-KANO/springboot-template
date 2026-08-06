# ============================================================================
# sqs-queue モジュール
#
# Step Functions の "Pattern A" (arn:aws:states:::sqs:sendMessage.waitForTaskToken) が
# タスクトークン付きメッセージを送る先。adapter-messaging の SettlementTaskListener が
# @SqsListener("${settlement.sqs.queue-name}") で受信し、settlementReconciliationJob を起動する。
#
# 本テンプレートの実運用のデフォルトは "Pattern B" (ECS RunTask.waitForTaskToken、
# statemachine/settlement-reconciliation.asl.json) だが、Pattern A
# (statemachine/settlement-reconciliation-sqs.asl.json、docker-compose のローカル環境で
# 実際に動かしているのと同じ構成) を本番相当環境でも選択できるように、このキュー自体は
# 常に作っておく (envs/*/main.tf 側でどちらの ASL を使うかを切り替える)。
# ============================================================================

resource "aws_sqs_queue" "dlq" {
  count                     = var.enable_dlq ? 1 : 0
  name                      = "${var.name}-dlq"
  message_retention_seconds = var.message_retention_seconds

  tags = var.tags
}

resource "aws_sqs_queue" "this" {
  name                       = var.name
  visibility_timeout_seconds = var.visibility_timeout_seconds
  message_retention_seconds  = var.message_retention_seconds

  redrive_policy = var.enable_dlq ? jsonencode({
    deadLetterTargetArn = aws_sqs_queue.dlq[0].arn
    maxReceiveCount     = var.max_receive_count
  }) : null

  tags = var.tags
}
