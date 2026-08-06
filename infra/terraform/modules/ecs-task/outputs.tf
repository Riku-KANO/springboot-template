output "task_definition_arn" {
  value = aws_ecs_task_definition.this.arn
}

output "task_definition_arn_no_revision" {
  description = "リビジョン番号を含まないファミリ ARN。IAM ポリシー (iam モジュールの ecs_task_definition_arn_pattern) や ASL 定義から参照する際に使う。"
  value       = "arn:aws:ecs:${var.region}:${data.aws_caller_identity.current.account_id}:task-definition/${aws_ecs_task_definition.this.family}"
}

output "family" {
  value = aws_ecs_task_definition.this.family
}

output "container_name" {
  value = "batch"
}

output "log_group_name" {
  value = aws_cloudwatch_log_group.this.name
}

data "aws_caller_identity" "current" {}
