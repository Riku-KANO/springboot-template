# ============================================================================
# sfn-state-machine モジュール
#
# 消込バッチ起動用のステートマシンを1つ作る汎用モジュール。ASL 定義そのもの (どのパターンで
# バッチを起動するか) はこのモジュールの関心事ではなく、呼び出し側 (envs/*/main.tf) が
# statemachine/settlement-reconciliation.asl.json (Pattern B: ECS RunTask) と
# statemachine/settlement-reconciliation-sqs.asl.json (Pattern A: SQS) のどちらを
# templatefile() でレンダリングして渡すかを決める。
# ============================================================================

resource "aws_cloudwatch_log_group" "this" {
  count             = var.logging_level != "OFF" ? 1 : 0
  name              = "/aws/vendedlogs/states/${var.name}"
  retention_in_days = var.log_retention_days

  tags = var.tags
}

resource "aws_sfn_state_machine" "this" {
  name       = var.name
  role_arn   = var.role_arn
  type       = var.type
  definition = var.definition

  dynamic "logging_configuration" {
    for_each = var.logging_level != "OFF" ? [1] : []
    content {
      log_destination        = "${aws_cloudwatch_log_group.this[0].arn}:*"
      include_execution_data = true
      level                  = var.logging_level
    }
  }

  tags = var.tags
}
