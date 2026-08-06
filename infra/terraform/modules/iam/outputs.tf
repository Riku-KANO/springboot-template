output "ecs_task_execution_role_arn" {
  value = aws_iam_role.ecs_task_execution.arn
}

output "ecs_api_task_role_arn" {
  value = aws_iam_role.ecs_api_task.arn
}

output "ecs_batch_task_role_arn" {
  value = aws_iam_role.ecs_batch_task.arn
}

output "sfn_execution_role_arn" {
  value = aws_iam_role.sfn_execution.arn
}

output "github_actions_role_arn" {
  value = aws_iam_role.github_actions.arn
}

output "github_oidc_provider_arn" {
  value = local.github_oidc_provider_arn
}
