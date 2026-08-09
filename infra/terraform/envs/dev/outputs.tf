output "ecr_repository_url" {
  value = module.ecr.repository_url
}

output "rds_endpoint" {
  value = module.rds.endpoint
}

output "db_credentials_secret_arn" {
  value = aws_secretsmanager_secret.db_credentials.arn
}

output "settlement_bucket_id" {
  value = module.s3_settlements.bucket_id
}

output "settlement_queue_url" {
  value = module.sqs_settlement_tasks.queue_url
}

output "ecs_cluster_name" {
  value = module.ecs_cluster.cluster_name
}

output "ecs_api_service_name" {
  value = module.ecs_service_api.service_name
}

output "ecs_batch_task_family" {
  value = module.ecs_task_batch.family
}

output "state_machine_arn" {
  value = module.sfn_settlement_reconciliation.arn
}

output "github_actions_role_arn" {
  description = ".github/workflows/docker-build-push.yml が configure-aws-credentials で assume する ロール ARN。"
  value       = module.iam.github_actions_role_arn
}

output "github_actions_plan_role_arn" {
  value = module.iam.github_actions_plan_role_arn
}

output "api_endpoint" {
  value = module.alb.endpoint
}
