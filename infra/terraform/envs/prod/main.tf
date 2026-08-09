# ============================================================================
# envs/prod - 本番環境のルートモジュール
#
# dev/stg との構造上の違い:
#   - RDS: Multi-AZ / 削除保護 / 最終スナップショット取得を、変数を介さず直接 true/false 固定
#     (tfvars で緩められない)。apply_immediately も常に false (メンテナンスウィンドウ適用)。
#   - ECS: FARGATE_SPOT は使わない (固定)。オートスケーリングの下限を2 (単一 AZ 障害を
#     跨いでも最低限のキャパシティを残す) に設定。
#   - ECR: IMMUTABLE タグ + 失効ポリシーを長め (30日) に。
#   - ログ保持期間を1年に (log_retention_days の既定値そのものが prod だけ長い)。
#   - GitHub OIDC の許可 ref を refs/heads/main だけに限定 (タグからの手動デプロイは許可しない)。
# ============================================================================

locals {
  env_name = "prod"
  name     = "template-${local.env_name}"

  common_tags = {
    Project     = "springboot-template"
    Environment = local.env_name
    ManagedBy   = "terraform"
  }

  batch_task_family = "${local.name}-batch"

  github_oidc_provider_arn = "arn:aws:iam::${var.account_id}:oidc-provider/token.actions.githubusercontent.com"

  ecs_task_definition_arn_pattern = "arn:aws:ecs:${var.aws_region}:${var.account_id}:task-definition/${local.batch_task_family}"

  asl_vars_ecs_run_task = {
    cluster_arn                = module.ecs_cluster.cluster_arn
    task_definition_arn        = module.ecs_task_batch.task_definition_arn
    container_name             = module.ecs_task_batch.container_name
    private_subnet_id_1        = module.network.private_subnet_ids[0]
    private_subnet_id_2        = module.network.private_subnet_ids[1]
    ecs_task_security_group_id = module.network.ecs_task_security_group_id
    spring_profiles            = "${local.env_name},batch"
    sns_topic_arn              = aws_sns_topic.settlement_notifications.arn
  }

  asl_vars_sqs = {
    sqs_queue_url = module.sqs_settlement_tasks.queue_url
    sns_topic_arn = aws_sns_topic.settlement_notifications.arn
  }

  asl_definition = (
    var.settlement_dispatch_pattern == "sqs"
    ? templatefile("${path.module}/../../statemachine/settlement-reconciliation-sqs.asl.json", local.asl_vars_sqs)
    : templatefile("${path.module}/../../statemachine/settlement-reconciliation.asl.json", local.asl_vars_ecs_run_task)
  )
}

module "network" {
  source = "../../modules/network"

  name                 = local.name
  vpc_cidr_block       = var.vpc_cidr_block
  availability_zones   = var.availability_zones
  public_subnet_cidrs  = var.public_subnet_cidrs
  private_subnet_cidrs = var.private_subnet_cidrs
  enable_nat_gateway   = true
  single_nat_gateway   = false # prod は必ず AZ ごとに NAT Gateway を持つ (固定)
  tags                 = local.common_tags
}

module "ecr" {
  source = "../../modules/ecr"

  name                       = local.name
  image_tag_mutability       = "IMMUTABLE"
  untagged_image_expiry_days = 30
  tags                       = local.common_tags
}

module "s3_settlements" {
  source = "../../modules/s3-bucket"

  bucket_name        = var.settlement_bucket_name
  versioning_enabled = true
  force_destroy      = false # prod は絶対に force_destroy しない (固定)
  tags               = local.common_tags
}

module "sqs_settlement_tasks" {
  source = "../../modules/sqs-queue"

  name = var.settlement_queue_name
  tags = local.common_tags
}

module "ecs_cluster" {
  source = "../../modules/ecs-cluster"

  name                       = local.name
  container_insights_enabled = true
  fargate_spot_enabled       = false # prod は SPOT による中断を許容しない (固定)
  tags                       = local.common_tags
}

# --- DB 認証情報 (dev/stg と同じ設計。application-prod.yml が読む名前と一致させる) ---

resource "random_password" "db" {
  length  = 32
  special = false
}

resource "aws_secretsmanager_secret" "db_credentials" {
  name = "template/${local.env_name}/db-credentials"
  tags = local.common_tags
}

resource "aws_secretsmanager_secret_version" "db_credentials" {
  secret_id = aws_secretsmanager_secret.db_credentials.id
  secret_string = jsonencode({
    username = "template_admin"
    password = random_password.db.result
  })
}

module "rds" {
  source = "../../modules/rds-postgres"

  name                         = local.name
  vpc_id                       = module.network.vpc_id
  subnet_ids                   = module.network.private_subnet_ids
  allowed_security_group_ids   = [module.network.rds_security_group_id]
  master_username              = "template_admin"
  master_password              = random_password.db.result
  instance_class               = var.rds_instance_class
  allocated_storage_gb         = var.rds_allocated_storage_gb
  max_allocated_storage_gb     = var.rds_max_allocated_storage_gb
  backup_retention_period_days = var.rds_backup_retention_period_days
  performance_insights_enabled = true
  apply_immediately            = false # 常にメンテナンスウィンドウでの適用 (固定)

  # 本番の安全装置。変数を介さずここで固定する (tfvars では緩められない)。
  multi_az            = true
  deletion_protection = true
  skip_final_snapshot = false

  tags = local.common_tags
}

module "iam" {
  source = "../../modules/iam"

  name                              = local.name
  region                            = var.aws_region
  account_id                        = var.account_id
  db_secret_arn                     = aws_secretsmanager_secret.db_credentials.arn
  ssm_parameter_path_arn            = "arn:aws:ssm:${var.aws_region}:${var.account_id}:parameter/template/${local.env_name}/*"
  settlement_bucket_arn             = module.s3_settlements.bucket_arn
  settlement_queue_arn              = module.sqs_settlement_tasks.queue_arn
  ecs_task_definition_arn_pattern   = local.ecs_task_definition_arn_pattern
  create_github_oidc_provider       = false # dev が作った OIDC プロバイダを再利用する (固定)
  existing_github_oidc_provider_arn = local.github_oidc_provider_arn
  github_repository                 = var.github_repository
  github_oidc_allowed_refs          = var.github_oidc_allowed_refs
  github_oidc_allowed_environments  = [local.env_name]
  ecr_repository_arn                = module.ecr.repository_arn
  terraform_state_bucket_arn        = "arn:aws:s3:::${var.terraform_state_bucket_name}"
  terraform_lock_table_arn          = "arn:aws:dynamodb:${var.aws_region}:${var.account_id}:table/${var.terraform_lock_table_name}"
  tags                              = local.common_tags
}

module "ecs_task_batch" {
  source = "../../modules/ecs-task"

  name               = local.batch_task_family
  image_uri          = "${module.ecr.repository_url}:latest"
  spring_profiles    = "${local.env_name},batch"
  cpu                = var.ecs_batch_cpu
  memory             = var.ecs_batch_memory
  execution_role_arn = module.iam.ecs_task_execution_role_arn
  task_role_arn      = module.iam.ecs_batch_task_role_arn
  environment = {
    SPRING_DATASOURCE_URL      = "jdbc:postgresql://${module.rds.address}:${module.rds.port}/${module.rds.db_name}"
    SPRING_DATASOURCE_USERNAME = "template_admin"
    SPRING_R2DBC_URL           = "r2dbc:postgresql://${module.rds.address}:${module.rds.port}/${module.rds.db_name}"
    SPRING_R2DBC_USERNAME      = "template_admin"
  }
  secrets = {
    SPRING_DATASOURCE_PASSWORD = "${aws_secretsmanager_secret.db_credentials.arn}:password::"
    SPRING_R2DBC_PASSWORD      = "${aws_secretsmanager_secret.db_credentials.arn}:password::"
  }
  log_retention_days = var.log_retention_days
  region             = var.aws_region
  tags               = local.common_tags
}

module "alb" {
  source = "../../modules/alb"

  name                = local.name
  vpc_id              = module.network.vpc_id
  subnet_ids          = module.network.public_subnet_ids
  security_group_ids  = [module.network.alb_security_group_id]
  certificate_arn     = var.api_certificate_arn
  hosted_zone_id      = var.api_hosted_zone_id
  domain_name         = var.api_domain_name
  deletion_protection = true
  tags                = local.common_tags
}

module "ecs_service_api" {
  source = "../../modules/ecs-service"

  name                   = "${local.name}-api"
  cluster_arn            = module.ecs_cluster.cluster_arn
  image_uri              = "${module.ecr.repository_url}:latest"
  spring_profiles        = local.env_name
  cpu                    = var.ecs_api_cpu
  memory                 = var.ecs_api_memory
  desired_count          = var.ecs_api_desired_count
  min_capacity           = var.ecs_api_min_capacity
  max_capacity           = var.ecs_api_max_capacity
  cpu_target_utilization = var.ecs_api_cpu_target_utilization
  subnet_ids             = module.network.private_subnet_ids
  security_group_ids     = [module.network.ecs_service_security_group_id]
  assign_public_ip       = false
  execution_role_arn     = module.iam.ecs_task_execution_role_arn
  task_role_arn          = module.iam.ecs_api_task_role_arn
  target_group_arn       = module.alb.target_group_arn
  environment = {
    SPRING_DATASOURCE_URL      = "jdbc:postgresql://${module.rds.address}:${module.rds.port}/${module.rds.db_name}"
    SPRING_DATASOURCE_USERNAME = "template_admin"
    SPRING_R2DBC_URL           = "r2dbc:postgresql://${module.rds.address}:${module.rds.port}/${module.rds.db_name}"
    SPRING_R2DBC_USERNAME      = "template_admin"
  }
  secrets = {
    SPRING_DATASOURCE_PASSWORD = "${aws_secretsmanager_secret.db_credentials.arn}:password::"
    SPRING_R2DBC_PASSWORD      = "${aws_secretsmanager_secret.db_credentials.arn}:password::"
  }
  log_retention_days = var.log_retention_days
  region             = var.aws_region
  tags               = local.common_tags
}

resource "aws_sns_topic" "settlement_notifications" {
  name = "${local.name}-settlement-notifications"
  tags = local.common_tags
}

module "monitoring" {
  source = "../../modules/monitoring"

  name                    = local.name
  alarm_topic_arn         = aws_sns_topic.settlement_notifications.arn
  alarm_email             = var.alarm_email
  alb_arn_suffix          = module.alb.arn_suffix
  target_group_arn_suffix = module.alb.target_group_arn_suffix
  ecs_cluster_name        = module.ecs_cluster.cluster_name
  ecs_service_name        = module.ecs_service_api.service_name
  rds_instance_id         = module.rds.instance_id
  sqs_dlq_name            = module.sqs_settlement_tasks.dlq_name
  tags                    = local.common_tags
}

module "sfn_settlement_reconciliation" {
  source = "../../modules/sfn-state-machine"

  name               = "${local.name}-settlement-reconciliation"
  definition         = local.asl_definition
  role_arn           = module.iam.sfn_execution_role_arn
  log_retention_days = var.log_retention_days
  logging_level      = "ERROR"
  tags               = local.common_tags
}
